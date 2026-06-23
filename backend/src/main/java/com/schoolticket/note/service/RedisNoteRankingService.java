package com.schoolticket.note.service;

import com.schoolticket.dto.CursorPage;
import com.schoolticket.note.entity.Note;
import com.schoolticket.note.entity.NoteLike;
import com.schoolticket.note.mapper.NoteLikeMapper;
import com.schoolticket.note.mapper.NoteMapper;
import com.schoolticket.user.entity.User;
import com.schoolticket.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RedisNoteRankingService {

    private final StringRedisTemplate redis;
    private final NoteMapper noteMapper;
    private final NoteLikeMapper noteLikeMapper;
    private final UserMapper userMapper;

    private static final String KEY_LATEST  = "note:latest";
    private static final String KEY_HOTTEST = "note:hottest";
    private static final String KEY_MINE    = "note:mine:%d";
    private static final String FEED_FOLLOWING_KEY = "feed:following:%d";
    private static final String VO_KEY       = "note:vo:%d";
    private static final String LIKE_COUNT_KEY = "note:like:count:%d";
    private static final int    FEED_FOLLOWING_CAP = 800;
    private static final long   VO_TTL_SECONDS = 7 * 24 * 3600;

    // ==================== 写入 ====================

    /** 发布笔记时加入 latest ZSET 和自己的 mine ZSET */
    public void addNote(Note note) {
        long score = toEpochMilli(note.getCreateTime());
        redis.opsForZSet().add(KEY_LATEST, String.valueOf(note.getNoteId()), score);
        redis.opsForZSet().add(String.format(KEY_MINE, note.getUserId()), String.valueOf(note.getNoteId()), score);
    }

    /** 点赞时更新 hottest ZSET */
    public void updateLikeCount(Long noteId, long likeCount) {
        if (likeCount > 0) {
            redis.opsForZSet().add(KEY_HOTTEST, String.valueOf(noteId), likeCount);
        } else {
            redis.opsForZSet().remove(KEY_HOTTEST, String.valueOf(noteId));
        }
    }

    /** 取消点赞时降低热度分 */
    public void decrementLikeCount(Long noteId) {
        Double score = redis.opsForZSet().score(KEY_HOTTEST, String.valueOf(noteId));
        if (score != null && score > 0) {
            redis.opsForZSet().incrementScore(KEY_HOTTEST, String.valueOf(noteId), -1);
        }
    }

    /** 删除笔记时从所有 ZSET 移除 */
    public void removeNote(Note note) {
        removeNoteById(note.getNoteId(), note.getUserId());
    }

    /** 根据 noteId + userId 从所有 ZSET 移除（MQ 消费端 / 读时修复使用） */
    public void removeNoteById(Long noteId, Long userId) {
        redis.opsForZSet().remove(KEY_LATEST, String.valueOf(noteId));
        redis.opsForZSet().remove(KEY_HOTTEST, String.valueOf(noteId));
        if (userId != null) {
            redis.opsForZSet().remove(String.format(KEY_MINE, userId), String.valueOf(noteId));
        }
    }

    /** 仅从 latest + hottest ZSET 移除（读时修复使用，userId 未知时降级处理） */
    public void removeNoteFromGlobal(Long noteId) {
        redis.opsForZSet().remove(KEY_LATEST, String.valueOf(noteId));
        redis.opsForZSet().remove(KEY_HOTTEST, String.valueOf(noteId));
    }

    // ==================== 推模式：关注流收件箱 ====================

    /** 发布笔记时 fanout 到所有粉丝的收件箱 */
    public void fanoutToFollowers(Long authorId, Long noteId, long timestamp, Set<Long> fanIds) {
        if (fanIds == null || fanIds.isEmpty()) return;
        String member = String.valueOf(noteId);
        for (Long fanId : fanIds) {
            String key = String.format(FEED_FOLLOWING_KEY, fanId);
            redis.opsForZSet().add(key, member, timestamp);
            // 只保留最近 N 条
            redis.opsForZSet().removeRange(key, 0, -(FEED_FOLLOWING_CAP + 1));
        }
    }

    /** 删除笔记时从所有粉丝收件箱中移除 */
    public void removeFromFollowerInboxes(Long authorId, Long noteId, Set<Long> fanIds) {
        if (fanIds == null || fanIds.isEmpty()) return;
        String member = String.valueOf(noteId);
        for (Long fanId : fanIds) {
            redis.opsForZSet().remove(String.format(FEED_FOLLOWING_KEY, fanId), member);
        }
    }

    /** 新粉丝关注时，将被关注者的最近笔记补推到这个粉丝的收件箱 */
    public void backfillInboxForNewFan(Long fanId, Long authorId) {
        Set<String> recentIds = redis.opsForZSet().reverseRange(
                String.format(KEY_MINE, authorId), 0, 49);
        if (recentIds == null || recentIds.isEmpty()) return;
        String inboxKey = String.format(FEED_FOLLOWING_KEY, fanId);
        for (String nid : recentIds) {
            Double score = redis.opsForZSet().score(String.format(KEY_MINE, authorId), nid);
            if (score != null) {
                redis.opsForZSet().add(inboxKey, nid, score);
            }
        }
    }

    /** 取关时清除该用户笔记 */
    public void removeAuthorFromInbox(Long fanId, Long authorId) {
        Set<String> authorNoteIds = redis.opsForZSet().range(
                String.format(KEY_MINE, authorId), 0, -1);
        if (authorNoteIds == null || authorNoteIds.isEmpty()) return;
        String inboxKey = String.format(FEED_FOLLOWING_KEY, fanId);
        for (String nid : authorNoteIds) {
            redis.opsForZSet().remove(inboxKey, nid);
        }
    }

    // ==================== VO 缓存（note:vo:{noteId} Hash） ====================

    /** 发布笔记时写入 VO 缓存 */
    public void cacheNoteVO(Long noteId, Map<String, String> fields) {
        String key = String.format(VO_KEY, noteId);
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, VO_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** 批量读取 VO 缓存，返回 noteId → fields 的 map（miss 的不在 map 中） */
    public Map<Long, Map<String, String>> batchGetNoteVOs(List<Long> noteIds) {
        Map<Long, Map<String, String>> result = new LinkedHashMap<>();
        if (noteIds.isEmpty()) return result;

        for (Long nid : noteIds) {
            Map<Object, Object> entries = redis.opsForHash().entries(String.format(VO_KEY, nid));
            if (entries != null && !entries.isEmpty()) {
                Map<String, String> fields = new LinkedHashMap<>();
                for (Map.Entry<Object, Object> e : entries.entrySet()) {
                    fields.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
                result.put(nid, fields);
            }
        }
        return result;
    }

    /** 删除笔记时清除 VO 缓存 */
    public void deleteNoteVO(Long noteId) {
        redis.delete(String.format(VO_KEY, noteId));
    }

    /** 点赞/取消时更新 VO 中的 likeCount */
    public void updateVOLikeCount(Long noteId, long delta) {
        String key = String.format(VO_KEY, noteId);
        try {
            redis.opsForHash().increment(key, "likeCount", delta);
        } catch (Exception e) {
            // 兜底：读取当前值计算后回写
            Object current = redis.opsForHash().get(key, "likeCount");
            long newVal = delta;
            if (current != null) {
                try {
                    newVal = Long.parseLong(String.valueOf(current)) + delta;
                } catch (NumberFormatException ignored) {
                }
            }
            redis.opsForHash().put(key, "likeCount", String.valueOf(newVal));
        }
    }

    // ==================== 点赞计数器（Redis 先行） ====================

    public long incrLikeCount(Long noteId) {
        String key = String.format(LIKE_COUNT_KEY, noteId);
        try {
            return redis.opsForValue().increment(key, 1);
        } catch (Exception e) {
            // 可能 key 被其他类型占用，强制覆盖后重试
            redis.delete(key);
            redis.opsForValue().set(key, "1");
            return 1L;
        }
    }

    public long decrLikeCount(Long noteId) {
        String key = String.format(LIKE_COUNT_KEY, noteId);
        try {
            Long val = redis.opsForValue().decrement(key);
            return val == null ? 0 : val;
        } catch (Exception e) {
            redis.delete(key);
            redis.opsForValue().set(key, "0");
            return 0L;
        }
    }

    public long getLikeCount(Long noteId) {
        String val = redis.opsForValue().get(String.format(LIKE_COUNT_KEY, noteId));
        return val == null ? -1 : Long.parseLong(val);
    }

    public void setLikeCount(Long noteId, long count) {
        redis.opsForValue().set(String.format(LIKE_COUNT_KEY, noteId), String.valueOf(count));
    }

    // ==================== 游标分页查询 ====================

    public CursorPage<Map<String, Object>> getLatest(Long cursor, int pageSize, Long currentUserId) {
        return queryNotePage(KEY_LATEST, cursor, pageSize, currentUserId);
    }

    public CursorPage<Map<String, Object>> getHottest(Long cursor, int pageSize, Long currentUserId) {
        return queryNotePage(KEY_HOTTEST, cursor, pageSize, currentUserId);
    }

    public CursorPage<Map<String, Object>> getMine(Long userId, Long cursor, int pageSize, Long currentUserId) {
        return queryNotePage(String.format(KEY_MINE, userId), cursor, pageSize, currentUserId);
    }

    /** 查询单个 noteId 在 hottest ZSET 中的得分 */
    public Double getNoteHottestScore(Long noteId) {
        return redis.opsForZSet().score(KEY_HOTTEST, String.valueOf(noteId));
    }

    /** 冷启动：从数据库同步数据到 Redis */
    public void syncAllFromDB() {
        List<Note> allNotes = noteMapper.selectList(null);
        for (Note note : allNotes) {
            addNote(note);
        }
    }

    /** 冷启动：同步点赞数据到 hottest ZSET */
    public void syncLikesFromDB(Map<Long, Long> likeCountMap) {
        for (Map.Entry<Long, Long> entry : likeCountMap.entrySet()) {
            if (entry.getValue() > 0) {
                redis.opsForZSet().add(KEY_HOTTEST, String.valueOf(entry.getKey()), entry.getValue());
            }
        }
    }

    // ==================== 候选池（推荐流用） ====================

    /** 从最新池中捞出至多 count 个随机候选 noteId，扫描窗口为 [offset, offset+windowSize) */
    public List<Long> getCandidateIds(int count, int offset, int windowSize) {
        long total = Optional.ofNullable(redis.opsForZSet().zCard(KEY_LATEST)).orElse(0L);
        if (total == 0) return Collections.emptyList();
        int startRank = offset;
        int endRank = Math.min(startRank + windowSize - 1, (int) total - 1);
        if (startRank >= total) return Collections.emptyList();
        Set<String> idSet = redis.opsForZSet().reverseRange(KEY_LATEST, startRank, endRank);
        if (idSet == null || idSet.isEmpty()) return Collections.emptyList();
        List<Long> all = idSet.stream().map(Long::valueOf).collect(Collectors.toList());
        Collections.shuffle(all);
        return all.size() <= count ? all : all.subList(0, count);
    }

    public List<Long> getCandidateIds(int count) {
        return getCandidateIds(count, 0, count * 3);
    }

    public long getLatestTotal() {
        Long total = redis.opsForZSet().zCard(KEY_LATEST);
        return total == null ? 0 : total;
    }

    /** 批量获取多个作者的 note:mine ZSET（返回 noteId → score），最大每作者取 limit 条 */
    public Map<Long, Long> batchGetAuthorNoteIds(Set<Long> authorIds, int limitPerAuthor) {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (Long authorId : authorIds) {
            String key = String.format(KEY_MINE, authorId);
            Set<String> ids = redis.opsForZSet().reverseRange(key, 0, limitPerAuthor - 1);
            if (ids == null) continue;
            for (String idStr : ids) {
                Long nid = Long.valueOf(idStr);
                Double score = redis.opsForZSet().score(key, idStr);
                if (score != null) result.put(nid, score.longValue());
            }
        }
        return result;
    }

    /** 读取关注流收件箱（推模式），按时间倒序游标分页 */
    public CursorPage<Map<String, Object>> getFollowingFeedInbox(Long userId, Long cursor, int pageSize) {
        String key = String.format(FEED_FOLLOWING_KEY, userId);
        long total = Optional.ofNullable(redis.opsForZSet().zCard(key)).orElse(0L);
        if (total == 0) return CursorPage.of(Collections.emptyList(), null, false, 0);

        long max = cursor != null ? cursor - 1 : Long.MAX_VALUE;
        Set<String> noteIdStrs = redis.opsForZSet().reverseRangeByScore(key, 0, max, 0, pageSize);
        if (noteIdStrs == null || noteIdStrs.isEmpty())
            return CursorPage.of(Collections.emptyList(), null, false, total);

        List<Long> noteIds = noteIdStrs.stream().map(Long::valueOf).collect(Collectors.toList());

        // 计算 lastScore 用于 nextCursor
        double lastScore = 0;
        for (String nid : noteIdStrs) {
            Double s = redis.opsForZSet().score(key, nid);
            if (s != null) lastScore = s;
        }

        boolean hasMore = noteIds.size() >= pageSize;
        // 简单封装 noteIds，VO 组装由 NoteService.assembleNoteVOsWithRepair 完成
        List<Map<String, Object>> records = noteIds.stream().map(nid -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("noteId", nid);
            return m;
        }).collect(Collectors.toList());

        return CursorPage.of(records, hasMore ? String.valueOf((long) lastScore) : null, hasMore, total);
    }

    // ==================== 内部 ====================

    private CursorPage<Map<String, Object>> queryNotePage(String key, Long cursor, int pageSize, Long currentUserId) {
        long total = Optional.ofNullable(redis.opsForZSet().zCard(key)).orElse(0L);
        if (total == 0) return CursorPage.of(Collections.emptyList(), null, false, 0);

        long max = cursor != null ? cursor - 1 : Long.MAX_VALUE;
        Set<String> noteIdStrs = redis.opsForZSet().reverseRangeByScore(key, 0, max, 0, pageSize);
        if (noteIdStrs == null || noteIdStrs.isEmpty())
            return CursorPage.of(Collections.emptyList(), null, false, total);

        List<Long> noteIds = noteIdStrs.stream().map(Long::valueOf).collect(Collectors.toList());

        // 查笔记
        List<Note> notes = noteMapper.selectBatchIds(noteIds);
        Map<Long, Note> noteMap = notes.stream().collect(Collectors.toMap(Note::getNoteId, n -> n));

        // 查用户
        Set<Long> userIds = notes.stream().map(Note::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = new HashMap<>();
        if (!userIds.isEmpty())
            userMapper.selectBatchIds(userIds).forEach(u -> userMap.put(u.getUserId(), u));

        // 查点赞数 (从 hottest ZSET 获取)
        Map<Long, Long> likeCountMap = new HashMap<>();
        for (Long nid : noteIds) {
            Double score = redis.opsForZSet().score(KEY_HOTTEST, String.valueOf(nid));
            if (score != null) likeCountMap.put(nid, score.longValue());
        }

        // 查当前用户是否点赞 (MySQL)
        Set<Long> likedSet = Collections.emptySet();
        if (currentUserId != null && !noteIds.isEmpty()) {
            likedSet = noteLikeMapper.selectList(
                    new LambdaQueryWrapper<NoteLike>()
                            .in(NoteLike::getNoteId, noteIds)
                            .eq(NoteLike::getUserId, currentUserId))
                    .stream()
                    .map(NoteLike::getNoteId)
                    .collect(Collectors.toSet());
        }

        // 组装结果，保持 ZSET 排序顺序
        double lastScore = 0;
        List<Map<String, Object>> records = new ArrayList<>();
        for (Long nid : noteIds) {
            Note note = noteMap.get(nid);
            if (note == null) continue;
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("noteId", note.getNoteId());
            vo.put("content", note.getContent());
            vo.put("createTime", note.getCreateTime());
            vo.put("likeCount", likeCountMap.getOrDefault(nid, 0L));
            vo.put("isLiked", likedSet.contains(nid));
            User author = userMap.get(note.getUserId());
            if (author != null) {
                vo.put("userId", author.getUserId());
                vo.put("nickname", author.getNickname());
                vo.put("phone", author.getPhone());
            }
            records.add(vo);

            Double score = redis.opsForZSet().score(key, String.valueOf(nid));
            if (score != null) lastScore = score;
        }

        boolean hasMore = records.size() >= pageSize && (cursor == null || records.size() == pageSize);
        return CursorPage.of(records, hasMore ? String.valueOf((long) lastScore) : null, hasMore, total);
    }

    private static long toEpochMilli(java.time.LocalDateTime dt) {
        return dt.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }
}

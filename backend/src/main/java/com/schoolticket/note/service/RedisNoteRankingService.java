package com.schoolticket.note.service;

import com.schoolticket.dto.CursorPage;
import com.schoolticket.note.entity.Note;
import com.schoolticket.note.entity.NoteLike;
import com.schoolticket.note.mapper.NoteLikeMapper;
import com.schoolticket.note.mapper.NoteMapper;
import com.schoolticket.user.entity.User;
import com.schoolticket.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RedisNoteRankingService {

    private final StringRedisTemplate redis;
    private final NoteMapper noteMapper;
    private final UserMapper userMapper;
    private final NoteLikeMapper noteLikeMapper;

    private static final String KEY_LATEST  = "note:latest";
    private static final String KEY_MINE    = "note:mine:%d";
    private static final String FEED_FOLLOWING_KEY = "feed:following:%d";
    private static final String VO_KEY       = "note:vo:%d";
    private static final String LIKE_COUNT_KEY = "note:like:count:%d";
    private static final String USER_LIKES_KEY = "user:likes:%d";
    private static final int    FEED_FOLLOWING_CAP = 800;
    private static final long   VO_TTL_SECONDS = 7 * 24 * 3600;
    private static final long   LATEST_TTL_SECONDS = 86400;
    private static final long   MINE_TTL_SECONDS = 259200;
    private static final long   LIKE_COUNT_TTL_SECONDS = 604800;
    private static final long   USER_LIKES_TTL_SECONDS = 259200;
    private static final long   FOLLOW_FEED_TTL_SECONDS = 259200;

    // ==================== 笔记关联活动缓存（note:events:{noteId}） ====================

    private static final String NOTE_EVENTS_KEY = "note:events:%d";
    private static final long   NOTE_EVENTS_TTL_SECONDS = 7 * 24 * 3600; // 7天，与 VO 一致

    /** 缓存笔记关联的活动 ID 列表（逗号分隔字符串，空串表示无关联） */
    public void cacheNoteEvents(Long noteId, List<Long> eventIds) {
        String key = String.format(NOTE_EVENTS_KEY, noteId);
        String val = eventIds == null || eventIds.isEmpty() ? ""
                : eventIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        redis.opsForValue().set(key, val, NOTE_EVENTS_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /** 批量获取笔记关联的活动 ID（pipeline GET），返回 noteId → eventId 列表（miss 的不在 map 中）*/
    public Map<Long, List<Long>> batchGetNoteEventIds(List<Long> noteIds) {
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        if (noteIds.isEmpty()) return result;

        List<byte[]> rawKeys = new ArrayList<>();
        for (Long nid : noteIds) {
            rawKeys.add(String.format(NOTE_EVENTS_KEY, nid).getBytes(StandardCharsets.UTF_8));
        }

        List<Object> results = redis.executePipelined(
                (org.springframework.data.redis.connection.RedisConnection connection) -> {
            for (byte[] key : rawKeys) {
                connection.stringCommands().get(key);
            }
            return null;
        });

        for (int i = 0; i < noteIds.size(); i++) {
            Object obj = results.get(i);
            if (obj instanceof String s && !s.isEmpty()) {
                List<Long> eventIds = java.util.Arrays.stream(s.split(","))
                        .map(Long::valueOf)
                        .collect(java.util.stream.Collectors.toList());
                result.put(noteIds.get(i), eventIds);
            }
        }
        return result;
    }

    /** 删除笔记关联活动缓存 */
    public void deleteNoteEventsCache(Long noteId) {
        redis.delete(String.format(NOTE_EVENTS_KEY, noteId));
    }

    // ==================== 写入 ====================

    /** 发布笔记时加入 latest ZSET 和自己的 mine ZSET */
    public void addNote(Note note) {
        long score = toEpochMilli(note.getCreateTime());
        redis.opsForZSet().add(KEY_LATEST, String.valueOf(note.getNoteId()), score);
        redis.expire(KEY_LATEST, LATEST_TTL_SECONDS, TimeUnit.SECONDS);
        String mineKey = String.format(KEY_MINE, note.getUserId());
        redis.opsForZSet().add(mineKey, String.valueOf(note.getNoteId()), score);
        redis.expire(mineKey, MINE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /** 删除笔记时从所有 ZSET 移除 */
    public void removeNote(Note note) {
        removeNoteById(note.getNoteId(), note.getUserId());
    }

    /** 根据 noteId + userId 从所有 ZSET 移除（MQ 消费端 / 读时修复使用） */
    public void removeNoteById(Long noteId, Long userId) {
        redis.opsForZSet().remove(KEY_LATEST, String.valueOf(noteId));
        if (userId != null) {
            redis.opsForZSet().remove(String.format(KEY_MINE, userId), String.valueOf(noteId));
        }
    }

    /** 从 latest ZSET 移除（读时修复使用，userId 未知时降级处理） */
    public void removeNoteFromGlobal(Long noteId) {
        redis.opsForZSet().remove(KEY_LATEST, String.valueOf(noteId));
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
            redis.expire(key, FOLLOW_FEED_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** 删除笔记时从所有粉丝收件箱中移除 */
    public void removeFromFollowerInboxes(Long authorId, Long noteId, Set<Long> fanIds) {
        if (fanIds == null || fanIds.isEmpty()) return;
        String member = String.valueOf(noteId);
        for (Long fanId : fanIds) {
            String key = String.format(FEED_FOLLOWING_KEY, fanId);
            redis.opsForZSet().remove(key, member);
            redis.expire(key, FOLLOW_FEED_TTL_SECONDS, TimeUnit.SECONDS);
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
        redis.expire(inboxKey, FOLLOW_FEED_TTL_SECONDS, TimeUnit.SECONDS);
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
        redis.expire(inboxKey, FOLLOW_FEED_TTL_SECONDS, TimeUnit.SECONDS);
    }

    // ==================== 大V 标记（bigv:ids Set） ====================

    private static final String BIGV_IDS_KEY = "bigv:ids";

    public void markBigV(Long userId) {
        redis.opsForSet().add(BIGV_IDS_KEY, String.valueOf(userId));
    }

    public void unmarkBigV(Long userId) {
        redis.opsForSet().remove(BIGV_IDS_KEY, String.valueOf(userId));
    }

    public boolean isBigV(Long userId) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(BIGV_IDS_KEY, String.valueOf(userId)));
    }

    /** 从关注列表中筛出大V ID */
    public Set<Long> filterBigVIds(Set<Long> followingIds) {
        if (followingIds == null || followingIds.isEmpty()) return Collections.emptySet();
        Set<Long> result = new HashSet<>();
        for (Long uid : followingIds) {
            if (isBigV(uid)) result.add(uid);
        }
        return result;
    }

    // ==================== 拉模式：从大V 发件箱拉取 ====================

    /**
     * 从多个大V 的 note:mine 中拉取帖子，按时间倒序合并。
     * 对每个大V 做 ZREVRANGEBYSCORE，收集 (noteId → score)，全局按 score 降序排列。
     */
    public Map<Long, Long> pullFromBigVs(Set<Long> bigVIds, Long cursor, int pageSize) {
        Map<Long, Long> allEntries = new LinkedHashMap<>();
        if (bigVIds == null || bigVIds.isEmpty()) return allEntries;

        long max = cursor != null ? cursor - 1 : Long.MAX_VALUE;

        for (Long authorId : bigVIds) {
            String key = String.format(KEY_MINE, authorId);
            Set<String> noteIdStrs = redis.opsForZSet().reverseRangeByScore(key, 0, max, 0, pageSize);
            if (noteIdStrs == null) continue;
            for (String nidStr : noteIdStrs) {
                Long noteId = Long.valueOf(nidStr);
                if (!allEntries.containsKey(noteId)) {
                    Double score = redis.opsForZSet().score(key, nidStr);
                    if (score != null) {
                        allEntries.put(noteId, score.longValue());
                    }
                }
            }
        }

        return allEntries.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    // ==================== VO 缓存（note:vo:{noteId} Hash） ====================

    /** 发布笔记时写入 VO 缓存 */
    public void cacheNoteVO(Long noteId, Map<String, String> fields) {
        String key = String.format(VO_KEY, noteId);
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, VO_TTL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** 批量读取 VO 缓存（pipeline HGETALL），返回 noteId → fields 的 map（miss 的不在 map 中） */
    public Map<Long, Map<String, String>> batchGetNoteVOs(List<Long> noteIds) {
        Map<Long, Map<String, String>> result = new LinkedHashMap<>();
        if (noteIds.isEmpty()) return result;

        // pipeline 所有 HGETALL
        List<byte[]> rawKeys = new ArrayList<>();
        for (Long nid : noteIds) {
            rawKeys.add(String.format(VO_KEY, nid).getBytes(StandardCharsets.UTF_8));
        }

        List<Object> results = redis.executePipelined(
                (org.springframework.data.redis.connection.RedisConnection connection) -> {
            for (byte[] key : rawKeys) {
                connection.hGetAll(key);
            }
            return null;
        });

        // 解析结果：StringRedisTemplate 将 HGETALL 结果反序列化为 Map<String, String>
        for (int i = 0; i < noteIds.size(); i++) {
            Object obj = results.get(i);
            if (obj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> raw = (Map<Object, Object>) obj;
                if (!raw.isEmpty()) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    for (Map.Entry<Object, Object> e : raw.entrySet()) {
                        fields.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                    result.put(noteIds.get(i), fields);
                }
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
        redis.expire(key, VO_TTL_SECONDS, TimeUnit.SECONDS);
    }

    // ==================== 点赞计数器（Redis 先行） ====================

    public long incrLikeCount(Long noteId) {
        String key = String.format(LIKE_COUNT_KEY, noteId);
        try {
            Long val = redis.opsForValue().increment(key, 1);
            redis.expire(key, LIKE_COUNT_TTL_SECONDS, TimeUnit.SECONDS);
            return val;
        } catch (Exception e) {
            redis.delete(key);
            redis.opsForValue().set(key, "1");
            redis.expire(key, LIKE_COUNT_TTL_SECONDS, TimeUnit.SECONDS);
            return 1L;
        }
    }

    public long decrLikeCount(Long noteId) {
        String key = String.format(LIKE_COUNT_KEY, noteId);
        try {
            Long val = redis.opsForValue().decrement(key);
            redis.expire(key, LIKE_COUNT_TTL_SECONDS, TimeUnit.SECONDS);
            return val == null ? 0 : val;
        } catch (Exception e) {
            redis.delete(key);
            redis.opsForValue().set(key, "0");
            redis.expire(key, LIKE_COUNT_TTL_SECONDS, TimeUnit.SECONDS);
            return 0L;
        }
    }

    public long getLikeCount(Long noteId) {
        String val = redis.opsForValue().get(String.format(LIKE_COUNT_KEY, noteId));
        return val == null ? -1 : Long.parseLong(val);
    }

    public void setLikeCount(Long noteId, long count) {
        String key = String.format(LIKE_COUNT_KEY, noteId);
        redis.opsForValue().set(key, String.valueOf(count));
        redis.expire(key, LIKE_COUNT_TTL_SECONDS, TimeUnit.SECONDS);
    }

    // ==================== 用户点赞 Set（user:likes:{userId}） ====================

    public void saddUserLike(Long userId, Long noteId) {
        String key = String.format(USER_LIKES_KEY, userId);
        redis.opsForSet().add(key, String.valueOf(noteId));
        redis.expire(key, USER_LIKES_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public void sremUserLike(Long userId, Long noteId) {
        String key = String.format(USER_LIKES_KEY, userId);
        redis.opsForSet().remove(key, String.valueOf(noteId));
        redis.expire(key, USER_LIKES_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public boolean isLiked(Long userId, Long noteId) {
        return Boolean.TRUE.equals(
                redis.opsForSet().isMember(String.format(USER_LIKES_KEY, userId), String.valueOf(noteId)));
    }

    /** 批量检查用户是否点赞（pipeline SISMEMBER），返回已点赞的 noteId 集合 */
    public Set<Long> batchCheckLiked(Long userId, List<Long> noteIds) {
        Set<Long> result = new HashSet<>();
        if (userId == null || noteIds.isEmpty()) return result;
        byte[] keyBytes = String.format(USER_LIKES_KEY, userId).getBytes(StandardCharsets.UTF_8);

        List<Object> results = redis.executePipelined(
                (org.springframework.data.redis.connection.RedisConnection connection) -> {
            for (Long nid : noteIds) {
                connection.sIsMember(keyBytes, String.valueOf(nid).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        int i = 0;
        for (Long nid : noteIds) {
            if (Boolean.TRUE.equals(results.get(i))) {
                result.add(nid);
            }
            i++;
        }
        return result;
    }

    /** 冷启动：从 note_like 表按用户分组写入 Redis Set */
    public void syncUserLikesFromMap(Map<Long, Set<Long>> userLikesMap) {
        for (Map.Entry<Long, Set<Long>> entry : userLikesMap.entrySet()) {
            String key = String.format(USER_LIKES_KEY, entry.getKey());
            String[] members = entry.getValue().stream().map(String::valueOf).toArray(String[]::new);
            redis.opsForSet().add(key, members);
            redis.expire(key, USER_LIKES_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    // ==================== 游标分页查询 ====================

    public CursorPage<Map<String, Object>> getMine(Long userId, Long cursor, int pageSize, Long currentUserId) {
        return queryNotePage(String.format(KEY_MINE, userId), cursor, pageSize, currentUserId);
    }

    /** 冷启动：从数据库同步最新候选池，不再同步用户级数据 */
    public void syncAllFromDB() {
        List<Note> allNotes = noteMapper.selectList(null);
        for (Note note : allNotes) {
            long score = toEpochMilli(note.getCreateTime());
            redis.opsForZSet().add(KEY_LATEST, String.valueOf(note.getNoteId()), score);
        }
        redis.expire(KEY_LATEST, LATEST_TTL_SECONDS, TimeUnit.SECONDS);
    }

    // ==================== 懒加载：按用户按需加载 ====================

    /** 确保 note:latest 存在，过期则从 DB 重建 */
    public void ensureLatestLoaded() {
        if (Boolean.TRUE.equals(redis.hasKey(KEY_LATEST))) return;
        syncAllFromDB();
    }

    /** 确保 note:mine:{userId} 存在，过期则从 DB 重建 */
    public void ensureMineLoaded(Long userId) {
        String key = String.format(KEY_MINE, userId);
        if (Boolean.TRUE.equals(redis.hasKey(key))) return;
        List<Note> notes = noteMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Note>()
                        .eq(Note::getUserId, userId));
        for (Note note : notes) {
            long score = toEpochMilli(note.getCreateTime());
            redis.opsForZSet().add(key, String.valueOf(note.getNoteId()), score);
        }
        redis.expire(key, MINE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /** 确保 user:likes:{userId} 存在，过期则从 DB 重建 */
    public void ensureUserLikesLoaded(Long userId) {
        String key = String.format(USER_LIKES_KEY, userId);
        if (Boolean.TRUE.equals(redis.hasKey(key))) return;
        List<NoteLike> likes = noteLikeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NoteLike>()
                        .eq(NoteLike::getUserId, userId));
        if (!likes.isEmpty()) {
            String[] members = likes.stream()
                    .map(l -> String.valueOf(l.getNoteId()))
                    .toArray(String[]::new);
            redis.opsForSet().add(key, members);
        }
        redis.expire(key, USER_LIKES_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /** 确保 feed:following:{userId} 存在，过期则从关注列表 + 作者笔记重建收件箱 */
    public void ensureInboxLoaded(Long userId, Set<Long> followingIds) {
        String inboxKey = String.format(FEED_FOLLOWING_KEY, userId);
        if (Boolean.TRUE.equals(redis.hasKey(inboxKey))) return;

        for (Long authorId : followingIds) {
            ensureMineLoaded(authorId);
            String mineKey = String.format(KEY_MINE, authorId);
            Set<String> noteIds = redis.opsForZSet().reverseRange(mineKey, 0, 49);
            if (noteIds == null) continue;
            for (String nid : noteIds) {
                Double score = redis.opsForZSet().score(mineKey, nid);
                if (score != null) {
                    redis.opsForZSet().add(inboxKey, nid, score);
                }
            }
        }
        redis.opsForZSet().removeRange(inboxKey, 0, -(FEED_FOLLOWING_CAP + 1));
        redis.expire(inboxKey, FOLLOW_FEED_TTL_SECONDS, TimeUnit.SECONDS);
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

        // 查点赞数 (从 like:count 获取)
        Map<Long, Long> likeCountMap = new HashMap<>();
        for (Long nid : noteIds) {
            long count = getLikeCount(nid);
            if (count >= 0) likeCountMap.put(nid, count);
        }

        // 查当前用户是否点赞 (Redis Set)
        Set<Long> likedSet = Collections.emptySet();
        if (currentUserId != null && !noteIds.isEmpty()) {
            likedSet = batchCheckLiked(currentUserId, noteIds);
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

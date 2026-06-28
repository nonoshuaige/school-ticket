package com.schoolticket.note.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schoolticket.common.BusinessException;
import com.schoolticket.dto.CursorPage;
import com.schoolticket.note.entity.Note;
import com.schoolticket.note.entity.NoteLike;
import com.schoolticket.note.mapper.NoteLikeMapper;
import com.schoolticket.note.mapper.NoteMapper;
import com.schoolticket.user.entity.User;
import com.schoolticket.user.mapper.UserMapper;
import com.schoolticket.user.service.RedisFollowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteMapper noteMapper;
    private final NoteLikeMapper noteLikeMapper;
    private final UserMapper userMapper;
    private final RedisNoteRankingService noteRankingService;
    private final RedisFollowService redisFollowService;
    private final BloomFilterService bloomFilterService;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redis;

    private static final String FEED_KEY = "feed:recommend:%d";
    private static final String FEED_FOLLOWING_KEY = "feed:following:%d";
    private static final int FEED_SNAPSHOT_SIZE = 200;
    private static final int FEED_TTL_MINUTES = 30;

    @Value("${feed.big-v-threshold:1000}")
    private int bigVThreshold;

    // ==================== 推荐流（快照拉取 — Session 级私有 Feed 队列） ====================

    /**
     * cursor == null → 下拉刷新：生成快照推入私有 List，LPOP 首页
     * cursor != null → 上拉加载：LPOP 下一页
     */
    public CursorPage<Map<String, Object>> recommendFeed(Long currentUserId, Long cursor, int pageSize) {
        long t0 = System.currentTimeMillis();
        Long effectiveUserId = currentUserId != null ? currentUserId : 0L;
        String feedKey = String.format(FEED_KEY, effectiveUserId);

        // 懒加载：全局候选池过期则从 DB 重建
        noteRankingService.ensureLatestLoaded();

        long tSnap = 0, tPush = 0, tPop = 0, tBloom = 0, tVO = 0;
        if (cursor == null) {
            long _t = System.currentTimeMillis();
            List<Long> snapshot = generateFeedSnapshot(effectiveUserId);
            tSnap = System.currentTimeMillis() - _t;

            _t = System.currentTimeMillis();
            redis.delete(feedKey);
            if (!snapshot.isEmpty()) {
                redis.opsForList().rightPushAll(feedKey, snapshot.stream().map(String::valueOf).toList());
                redis.expire(feedKey, FEED_TTL_MINUTES, TimeUnit.MINUTES);
            }
            tPush = System.currentTimeMillis() - _t;
        }

        long _t = System.currentTimeMillis();
        // pipeline 批量 LPOP
        List<Object> poppedRaw = redis.executePipelined(
                (org.springframework.data.redis.connection.RedisConnection connection) -> {
            byte[] keyBytes = feedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (int i = 0; i < pageSize; i++) {
                connection.lPop(keyBytes);
            }
            return null;
        });
        List<String> popped = new ArrayList<>();
        for (Object o : poppedRaw) {
            if (o == null) break;
            popped.add(String.valueOf(o));
        }
        tPop = System.currentTimeMillis() - _t;

        if (popped.isEmpty()) {
            log.info("recommendFeed total={}ms | snap={}ms push={}ms pop={}ms (empty)",
                    System.currentTimeMillis() - t0, tSnap, tPush, tPop);
            return CursorPage.of(Collections.emptyList(), null, false, 0);
        }

        List<Long> noteIds = popped.stream().map(Long::valueOf).collect(Collectors.toList());

        _t = System.currentTimeMillis();
        if (currentUserId != null) {
            bloomFilterService.addAll(currentUserId, noteIds);
        }
        tBloom = System.currentTimeMillis() - _t;

        _t = System.currentTimeMillis();
        List<Map<String, Object>> records = assembleNoteVOsWithRepair(noteIds, currentUserId);
        tVO = System.currentTimeMillis() - _t;

        Long remaining = redis.opsForList().size(feedKey);
        boolean hasMore = remaining != null && remaining > 0;

        log.info("recommendFeed total={}ms | snap={}ms push={}ms pop={}ms bloom={}ms vo={}ms",
                System.currentTimeMillis() - t0, tSnap, tPush, tPop, tBloom, tVO);
        return CursorPage.of(records, hasMore ? "0" : null, hasMore, records.size());
    }

    /** 从全站最新候选池中生成一个去重后的快照 ID 列表 */
    private List<Long> generateFeedSnapshot(Long effectiveUserId) {
        long t0 = System.currentTimeMillis();

        long tz = System.currentTimeMillis();
        long total = noteRankingService.getLatestTotal();
        long tZcard = System.currentTimeMillis() - tz;
        if (total == 0) return Collections.emptyList();

        int fetchSize = (int) Math.min(total, 600);

        long t1 = System.currentTimeMillis();
        List<Long> candidates = noteRankingService.getCandidateIds(fetchSize, 0, fetchSize);
        long tCand = System.currentTimeMillis() - t1;
        if (candidates.isEmpty()) return Collections.emptyList();

        long t2 = System.currentTimeMillis();
        List<Long> fresh = new ArrayList<>(bloomFilterService.batchFilterSeen(effectiveUserId, candidates));
        long tBloom = System.currentTimeMillis() - t2;

        long t3 = System.currentTimeMillis();
        List<Long> result = fresh.size() <= FEED_SNAPSHOT_SIZE ? fresh : fresh.subList(0, FEED_SNAPSHOT_SIZE);
        long tTrim = System.currentTimeMillis() - t3;

        log.info("generateFeedSnapshot total={}ms | zcard={}ms zrange={}ms bloom={}ms trim={}ms | candidates={} fresh={}",
                System.currentTimeMillis() - t0, tZcard, tCand, tBloom, tTrim, candidates.size(), fresh.size());
        return result;
    }

    // ==================== 关注流（推拉结合：普通用户推 + 大V 拉） ====================

    public CursorPage<Map<String, Object>> followingFeed(Long currentUserId, Long cursor, int pageSize) {
        if (currentUserId == null) return CursorPage.of(Collections.emptyList(), null, false, 0);

        Set<Long> followingIds = redisFollowService.getFollowingIds(currentUserId);
        if (followingIds.isEmpty()) return CursorPage.of(Collections.emptyList(), null, false, 0);

        // 1. 区分大V 和普通关注者
        Set<Long> bigVIds = noteRankingService.filterBigVIds(followingIds);
        Set<Long> normalIds = new HashSet<>(followingIds);
        normalIds.removeAll(bigVIds);

        // 2. 确保收件箱（只含普通用户的推内容）
        if (!normalIds.isEmpty()) {
            noteRankingService.ensureInboxLoaded(currentUserId, normalIds);
        }

        // 3. 从收件箱拉取（普通用户推内容）— overscan 2x 以便合并后有足够余量
        Map<Long, Long> inboxEntries = new LinkedHashMap<>();
        if (!normalIds.isEmpty()) {
            CursorPage<Map<String, Object>> inboxPage =
                    noteRankingService.getFollowingFeedInbox(currentUserId, cursor, pageSize * 2);
            String inboxKey = String.format(FEED_FOLLOWING_KEY, currentUserId);
            for (Map<String, Object> m : inboxPage.getRecords()) {
                Long nid = (Long) m.get("noteId");
                Double score = redis.opsForZSet().score(inboxKey, String.valueOf(nid));
                if (score != null) {
                    inboxEntries.putIfAbsent(nid, score.longValue());
                }
            }
        }

        // 4. 从大V 发件箱拉取
        Map<Long, Long> bigVEntries = noteRankingService.pullFromBigVs(bigVIds, cursor, pageSize);

        // 5. 合并两个来源的 (noteId → score)，按时间戳降序排列
        Map<Long, Long> allEntries = new LinkedHashMap<>();
        allEntries.putAll(inboxEntries);
        for (Map.Entry<Long, Long> e : bigVEntries.entrySet()) {
            allEntries.putIfAbsent(e.getKey(), e.getValue());
        }

        List<Map.Entry<Long, Long>> sorted = allEntries.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .collect(Collectors.toList());

        // 6. 截断取 pageSize 条，计算 nextCursor
        List<Long> pageNoteIds = new ArrayList<>();
        Long nextCursor = null;
        for (int i = 0; i < Math.min(pageSize, sorted.size()); i++) {
            Map.Entry<Long, Long> e = sorted.get(i);
            pageNoteIds.add(e.getKey());
            nextCursor = e.getValue();
        }
        boolean hasMore = sorted.size() > pageSize;

        if (pageNoteIds.isEmpty()) {
            return CursorPage.of(Collections.emptyList(), null, false, 0);
        }

        List<Map<String, Object>> records = assembleNoteVOsWithRepair(pageNoteIds, currentUserId);
        return CursorPage.of(records, nextCursor != null ? String.valueOf(nextCursor) : null, hasMore, records.size());
    }

    // ==================== 我的笔记（独立分页） ====================

    public CursorPage<Map<String, Object>> myNotes(Long userId, Long cursor, int pageSize) {
        noteRankingService.ensureMineLoaded(userId);
        return noteRankingService.getMine(userId, cursor, pageSize, userId);
    }

    // ==================== 笔记 CRUD ====================

    @Transactional
    public Note createNote(Long userId, String content) {
        Note note = new Note();
        note.setUserId(userId);
        note.setContent(content);
        noteMapper.insert(note);

        // 1. 写入 VO 缓存
        writeNoteVOToCache(note);

        // 2. 同步写入 latest + mine ZSET
        noteRankingService.addNote(note);

        // 3. 推拉结合：大V 只写 mine 不 fanout，普通用户 fanout
        long timestamp = toEpochMilli(note.getCreateTime());
        long fanCount = redisFollowService.getFansCount(userId);
        Set<Long> fanIds;
        if (fanCount < bigVThreshold) {
            fanIds = redisFollowService.getFanIds(userId);
            noteRankingService.fanoutToFollowers(userId, note.getNoteId(), timestamp, fanIds);
        } else {
            noteRankingService.markBigV(userId);
            fanIds = Collections.emptySet();
        }

        // 4. MQ 异步兜底
        Map<String, Object> msg = new HashMap<>();
        msg.put("noteId", note.getNoteId());
        msg.put("userId", userId);
        msg.put("createTime", timestamp);
        msg.put("bigV", fanCount >= bigVThreshold);
        msg.put("fanIds", fanIds.stream().map(String::valueOf).collect(Collectors.toList()));
        rabbitTemplate.convertAndSend("school.ticket.exchange", "note.create", msg);

        return note;
    }

    @Transactional
    public void deleteNote(Long noteId, Long userId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            throw new BusinessException(400, "笔记不存在");
        }
        if (!note.getUserId().equals(userId)) {
            throw new BusinessException(403, "只能删除自己的笔记");
        }

        // MyBatis-Plus @TableLogic: deleteById → UPDATE is_deleted = 1
        noteMapper.deleteById(noteId);

        // 清除 VO 缓存
        noteRankingService.deleteNoteVO(noteId);

        // 从所有粉丝收件箱移除
        Set<Long> fanIds = redisFollowService.getFanIds(note.getUserId());
        noteRankingService.removeFromFollowerInboxes(note.getUserId(), noteId, fanIds);

        // 从全局 + mine ZSET 移除
        noteRankingService.removeNote(note);

        // RabbitMQ 异步兜底
        Map<String, Object> msg = new HashMap<>();
        msg.put("noteId", noteId);
        msg.put("userId", note.getUserId());
        rabbitTemplate.convertAndSend("school.ticket.exchange", "note.delete", msg);
    }

    public Map<String, Object> getNoteDetail(Long noteId, Long currentUserId) {
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            throw new BusinessException(400, "笔记不存在");
        }

        Long likeCount = noteLikeMapper.selectCount(
                new LambdaQueryWrapper<NoteLike>().eq(NoteLike::getNoteId, noteId));

        boolean isLiked = false;
        if (currentUserId != null) {
            noteRankingService.ensureUserLikesLoaded(currentUserId);
            isLiked = noteRankingService.isLiked(currentUserId, noteId);
        }

        User author = userMapper.selectById(note.getUserId());

        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("noteId", note.getNoteId());
        vo.put("content", note.getContent());
        vo.put("createTime", note.getCreateTime());
        vo.put("likeCount", likeCount);
        vo.put("isLiked", isLiked);
        if (author != null) {
            vo.put("userId", author.getUserId());
            vo.put("nickname", author.getNickname());
            vo.put("phone", author.getPhone());
        }
        return vo;
    }

    // ==================== 点赞/取消（Redis 先行） ====================

    @Transactional
    public void like(Long noteId, Long userId) {
        // 1. Redis 先行
        long newCount = noteRankingService.incrLikeCount(noteId);
        noteRankingService.saddUserLike(userId, noteId);
        noteRankingService.updateVOLikeCount(noteId, 1);

        // 2. MySQL 落库（唯一约束防并发重复）
        try {
            NoteLike nl = new NoteLike();
            nl.setNoteId(noteId);
            nl.setUserId(userId);
            noteLikeMapper.insert(nl);
        } catch (DuplicateKeyException e) {
            // 幂等：已点过赞，不回滚 Redis
        }
    }

    @Transactional
    public void unlike(Long noteId, Long userId) {
        // 1. Redis 先行
        noteRankingService.decrLikeCount(noteId);
        noteRankingService.sremUserLike(userId, noteId);
        noteRankingService.updateVOLikeCount(noteId, -1);

        // 2. MySQL 删除
        noteLikeMapper.delete(
                new LambdaQueryWrapper<NoteLike>()
                        .eq(NoteLike::getNoteId, noteId)
                        .eq(NoteLike::getUserId, userId));

        // 3. 用 MySQL 实际值校准 Redis（防止计数漂移）
        long actualCount = noteLikeMapper.selectCount(
                new LambdaQueryWrapper<NoteLike>().eq(NoteLike::getNoteId, noteId));
        noteRankingService.setLikeCount(noteId, actualCount);
    }

    // ==================== 冷启动 ====================

    /** 冷启动：仅同步全局候选池 + 点赞计数器，用户级数据由懒加载按需构建 */
    public void syncAllToRedis() {
        // 1. 同步 latest 全局候选池
        noteRankingService.syncAllFromDB();

        // 2. 同步点赞计数器（note:like:count:{noteId}）
        List<NoteLike> allLikes = noteLikeMapper.selectList(null);
        Map<Long, Long> likeCountMap = new HashMap<>();
        for (NoteLike lk : allLikes) {
            likeCountMap.merge(lk.getNoteId(), 1L, Long::sum);
        }
        for (Map.Entry<Long, Long> entry : likeCountMap.entrySet()) {
            noteRankingService.setLikeCount(entry.getKey(), entry.getValue());
        }
    }

    // ==================== VO 组装（含读时修复 Read-Repair） ====================

    /**
     * 组装 VO：优先读 Redis VO 缓存，miss 查 MySQL 并回填。
     * Read-Repair: selectBatchIds 中 @TableLogic 过滤掉的 ID 从 Redis 全局 ZSET 清除。
     */
    private List<Map<String, Object>> assembleNoteVOsWithRepair(List<Long> noteIds, Long currentUserId) {
        if (noteIds.isEmpty()) return Collections.emptyList();

        // 1. 优先从 Redis VO 缓存批量读
        Map<Long, Map<String, String>> cachedVOs = noteRankingService.batchGetNoteVOs(noteIds);

        // 2. Cache miss 的 ID → MySQL 查 + 回填
        List<Long> missIds = noteIds.stream()
                .filter(nid -> !cachedVOs.containsKey(nid))
                .collect(Collectors.toList());

        if (!missIds.isEmpty()) {
            List<Note> notes = noteMapper.selectBatchIds(missIds);
            Map<Long, Note> noteMap = notes.stream().collect(Collectors.toMap(Note::getNoteId, n -> n));

            // Read-Repair: MySQL 中已不存在的 ID → 从 Redis 全局池清除
            for (Long nid : missIds) {
                if (!noteMap.containsKey(nid)) {
                    noteRankingService.removeNoteFromGlobal(nid);
                }
            }

            // 批量查用户信息
            Set<Long> missUserIds = notes.stream().map(Note::getUserId).collect(Collectors.toSet());
            Map<Long, User> missUserMap = new HashMap<>();
            if (!missUserIds.isEmpty()) {
                userMapper.selectBatchIds(missUserIds)
                        .forEach(u -> missUserMap.put(u.getUserId(), u));
            }

            // 回填 VO 缓存
            for (Note note : notes) {
                User author = missUserMap.get(note.getUserId());
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("content", note.getContent());
                fields.put("userId", String.valueOf(note.getUserId()));
                fields.put("nickname", author != null ? author.getNickname() : "");
                fields.put("phone", author != null ? author.getPhone() : "");
                fields.put("createTime", note.getCreateTime().toString());

                long likeCount = noteRankingService.getLikeCount(note.getNoteId());
                if (likeCount < 0) {
                    likeCount = noteLikeMapper.selectCount(
                            new LambdaQueryWrapper<NoteLike>().eq(NoteLike::getNoteId, note.getNoteId()));
                    noteRankingService.setLikeCount(note.getNoteId(), likeCount);
                }
                fields.put("likeCount", String.valueOf(likeCount));

                noteRankingService.cacheNoteVO(note.getNoteId(), fields);
                cachedVOs.put(note.getNoteId(), fields);
            }
        }

        // 3. 批量查当前用户点赞状态 (Redis Set)
        Set<Long> likedSet = Collections.emptySet();
        if (currentUserId != null) {
            noteRankingService.ensureUserLikesLoaded(currentUserId);
            List<Long> validIds = noteIds.stream().filter(cachedVOs::containsKey).collect(Collectors.toList());
            if (!validIds.isEmpty()) {
                likedSet = noteRankingService.batchCheckLiked(currentUserId, validIds);
            }
        }

        // 4. 按原始顺序组装
        List<Map<String, Object>> records = new ArrayList<>();
        for (Long nid : noteIds) {
            Map<String, String> fields = cachedVOs.get(nid);
            if (fields == null) continue;

            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("noteId", nid);
            vo.put("content", fields.get("content"));
            vo.put("createTime", fields.get("createTime"));
            vo.put("likeCount", Long.parseLong(fields.getOrDefault("likeCount", "0")));
            vo.put("isLiked", likedSet.contains(nid));
            vo.put("userId", Long.parseLong(fields.get("userId")));
            vo.put("nickname", fields.get("nickname"));
            vo.put("phone", fields.get("phone"));
            records.add(vo);
        }
        return records;
    }

    private void writeNoteVOToCache(Note note) {
        User author = userMapper.selectById(note.getUserId());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("content", note.getContent());
        fields.put("userId", String.valueOf(note.getUserId()));
        fields.put("nickname", author != null ? author.getNickname() : "");
        fields.put("phone", author != null ? author.getPhone() : "");
        fields.put("createTime", note.getCreateTime().toString());
        fields.put("likeCount", "0");
        noteRankingService.cacheNoteVO(note.getNoteId(), fields);
    }

    private static long toEpochMilli(LocalDateTime dt) {
        return dt.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }
}

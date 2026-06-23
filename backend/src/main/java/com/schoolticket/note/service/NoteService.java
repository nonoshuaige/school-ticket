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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    private static final int FEED_SNAPSHOT_SIZE = 200;
    private static final int FEED_TTL_MINUTES = 30;

    // ==================== 游标分页查询（仅保留 latest / hottest） ====================

    public CursorPage<Map<String, Object>> listNotes(Long cursor, int pageSize, Long currentUserId, String sort) {
        if ("hottest".equals(sort)) {
            return noteRankingService.getHottest(cursor, pageSize, currentUserId);
        }
        return noteRankingService.getLatest(cursor, pageSize, currentUserId);
    }

    // ==================== 推荐流（快照拉取 — Session 级私有 Feed 队列） ====================

    /**
     * cursor == null → 下拉刷新：生成快照推入私有 List，LPOP 首页
     * cursor != null → 上拉加载：LPOP 下一页
     */
    public CursorPage<Map<String, Object>> recommendFeed(Long currentUserId, Long cursor, int pageSize) {
        Long effectiveUserId = currentUserId != null ? currentUserId : 0L;
        String feedKey = String.format(FEED_KEY, effectiveUserId);

        if (cursor == null) {
            // 下拉刷新：重新生成快照
            List<Long> snapshot = generateFeedSnapshot(effectiveUserId);
            redis.delete(feedKey);
            if (!snapshot.isEmpty()) {
                redis.opsForList().rightPushAll(feedKey, snapshot.stream().map(String::valueOf).toList());
                redis.expire(feedKey, FEED_TTL_MINUTES, TimeUnit.MINUTES);
            }
        }

        // 上拉加载 / 首页：从私有 List LPOP 取 pageSize 条
        List<String> popped = new ArrayList<>();
        for (int i = 0; i < pageSize; i++) {
            String val = redis.opsForList().leftPop(feedKey);
            if (val == null) break;
            popped.add(val);
        }

        if (popped.isEmpty()) {
            return CursorPage.of(Collections.emptyList(), null, false, 0);
        }

        List<Long> noteIds = popped.stream().map(Long::valueOf).collect(Collectors.toList());

        // 标记已曝光
        if (currentUserId != null) {
            bloomFilterService.addAll(currentUserId, noteIds);
        }

        List<Map<String, Object>> records = assembleNoteVOsWithRepair(noteIds, currentUserId);

        // 还有剩余 → hasMore，nextCursor 为信号值（非 null 即触发上拉加载）
        Long remaining = redis.opsForList().size(feedKey);
        boolean hasMore = remaining != null && remaining > 0;
        return CursorPage.of(records, hasMore ? "0" : null, hasMore, records.size());
    }

    /** 从全站最新候选池中生成一个去重后的快照 ID 列表 */
    private List<Long> generateFeedSnapshot(Long effectiveUserId) {
        long total = noteRankingService.getLatestTotal();
        if (total == 0) return Collections.emptyList();

        // 取最新 600 条，shuffle 后过布隆，截取 FEED_SNAPSHOT_SIZE 条
        int fetchSize = (int) Math.min(total, 600);
        List<Long> candidates = noteRankingService.getCandidateIds(fetchSize, 0, fetchSize);
        if (candidates.isEmpty()) return Collections.emptyList();

        List<Long> fresh = new ArrayList<>();
        for (Long nid : candidates) {
            if (!bloomFilterService.mightContain(effectiveUserId, nid)) {
                fresh.add(nid);
                if (fresh.size() >= FEED_SNAPSHOT_SIZE) break;
            }
        }
        return fresh;
    }

    // ==================== 关注流（推模式：读自己收件箱） ====================

    public CursorPage<Map<String, Object>> followingFeed(Long currentUserId, Long cursor, int pageSize) {
        if (currentUserId == null) return CursorPage.of(Collections.emptyList(), null, false, 0);

        CursorPage<Map<String, Object>> inboxPage = noteRankingService.getFollowingFeedInbox(currentUserId, cursor, pageSize);
        if (inboxPage.getRecords().isEmpty()) return inboxPage;

        List<Long> noteIds = inboxPage.getRecords().stream()
                .map(m -> (Long) m.get("noteId"))
                .collect(Collectors.toList());

        List<Map<String, Object>> records = assembleNoteVOsWithRepair(noteIds, currentUserId);
        return CursorPage.of(records, inboxPage.getNextCursor(), inboxPage.isHasMore(), inboxPage.getTotal());
    }

    // ==================== 我的笔记（独立分页） ====================

    public CursorPage<Map<String, Object>> myNotes(Long userId, Long cursor, int pageSize) {
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

        // 3. 推模式：fanout 到所有粉丝收件箱
        Set<Long> fanIds = redisFollowService.getFanIds(userId);
        long timestamp = toEpochMilli(note.getCreateTime());
        noteRankingService.fanoutToFollowers(userId, note.getNoteId(), timestamp, fanIds);

        // 4. MQ 异步兜底
        Map<String, Object> msg = new HashMap<>();
        msg.put("noteId", note.getNoteId());
        msg.put("userId", userId);
        msg.put("createTime", timestamp);
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
            isLiked = noteLikeMapper.selectCount(
                    new LambdaQueryWrapper<NoteLike>()
                            .eq(NoteLike::getNoteId, noteId)
                            .eq(NoteLike::getUserId, currentUserId)) > 0;
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
        noteRankingService.updateVOLikeCount(noteId, 1);
        noteRankingService.updateLikeCount(noteId, newCount);

        // 2. MySQL 落库（唯一约束防并发重复）
        try {
            NoteLike nl = new NoteLike();
            nl.setNoteId(noteId);
            nl.setUserId(userId);
            noteLikeMapper.insert(nl);
        } catch (DuplicateKeyException e) {
            // 幂等：已点过赞，不回滚 Redis
        }

        // 3. MQ
        Map<String, Object> msg = new HashMap<>();
        msg.put("action", "like");
        msg.put("noteId", noteId);
        msg.put("likeCount", newCount);
        rabbitTemplate.convertAndSend("school.ticket.exchange", "note.like", msg);
    }

    @Transactional
    public void unlike(Long noteId, Long userId) {
        // 1. Redis 先行
        noteRankingService.decrLikeCount(noteId);
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
        noteRankingService.updateLikeCount(noteId, actualCount);

        // 4. MQ
        Map<String, Object> msg = new HashMap<>();
        msg.put("action", "unlike");
        msg.put("noteId", noteId);
        msg.put("likeCount", actualCount);
        rabbitTemplate.convertAndSend("school.ticket.exchange", "note.like", msg);
    }

    // ==================== 冷启动 ====================

    public void syncAllToRedis() {
        // 1. 同步 latest + mine ZSET
        noteRankingService.syncAllFromDB();

        // 2. 同步点赞计数 + VO 缓存
        List<NoteLike> allLikes = noteLikeMapper.selectList(null);
        Map<Long, Long> likeCountMap = new HashMap<>();
        for (NoteLike lk : allLikes) {
            likeCountMap.merge(lk.getNoteId(), 1L, Long::sum);
        }
        noteRankingService.syncLikesFromDB(likeCountMap);

        // 3. 回填 VO 缓存 + 点赞计数器 + 收件箱 fanout
        List<Note> allNotes = noteMapper.selectList(null);
        Set<Long> userIds = allNotes.stream().map(Note::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            userMapper.selectBatchIds(userIds).forEach(u -> userMap.put(u.getUserId(), u));
        }

        // 构建作者 → 粉丝映射，一次性 fanout
        Map<Long, Set<Long>> authorFanMap = new HashMap<>();

        for (Note note : allNotes) {
            long lc = likeCountMap.getOrDefault(note.getNoteId(), 0L);
            noteRankingService.setLikeCount(note.getNoteId(), lc);

            User author = userMap.get(note.getUserId());
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("content", note.getContent());
            fields.put("userId", String.valueOf(note.getUserId()));
            fields.put("nickname", author != null ? author.getNickname() : "");
            fields.put("phone", author != null ? author.getPhone() : "");
            fields.put("createTime", note.getCreateTime().toString());
            fields.put("likeCount", String.valueOf(lc));
            noteRankingService.cacheNoteVO(note.getNoteId(), fields);

            // 批量 fanout 到粉丝收件箱（每个作者只查一次粉丝列表）
            authorFanMap.computeIfAbsent(note.getUserId(),
                    k -> redisFollowService.getFanIds(k));
        }

        // 执行 fanout
        for (Note note : allNotes) {
            Set<Long> fanIds = authorFanMap.getOrDefault(note.getUserId(), Collections.emptySet());
            long timestamp = toEpochMilli(note.getCreateTime());
            noteRankingService.fanoutToFollowers(note.getUserId(), note.getNoteId(), timestamp, fanIds);
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

        // 3. 批量查当前用户点赞状态 (MySQL)
        Set<Long> likedSet = Collections.emptySet();
        if (currentUserId != null) {
            List<Long> validIds = noteIds.stream().filter(cachedVOs::containsKey).collect(Collectors.toList());
            if (!validIds.isEmpty()) {
                likedSet = noteLikeMapper.selectList(
                        new LambdaQueryWrapper<NoteLike>()
                                .in(NoteLike::getNoteId, validIds)
                                .eq(NoteLike::getUserId, currentUserId))
                        .stream().map(NoteLike::getNoteId).collect(Collectors.toSet());
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

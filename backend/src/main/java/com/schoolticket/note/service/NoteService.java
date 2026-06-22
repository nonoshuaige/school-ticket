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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
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

    // ==================== 游标分页查询（仅保留 latest / hottest） ====================

    public CursorPage<Map<String, Object>> listNotes(Long cursor, int pageSize, Long currentUserId, String sort) {
        if ("hottest".equals(sort)) {
            return noteRankingService.getHottest(cursor, pageSize, currentUserId);
        }
        return noteRankingService.getLatest(cursor, pageSize, currentUserId);
    }

    // ==================== 推荐流（布隆过滤器消重 + 无限滚动） ====================

    public CursorPage<Map<String, Object>> recommendFeed(Long currentUserId, Long cursor, int pageSize) {
        int offset = cursor != null ? cursor.intValue() : 0;
        int windowSize = pageSize * 3; // 每页扫描窗口大小与 pageSize 成正比，保证多页可用

        // Step 1: 从 ZSET offset 位置捞出窗口内的候选，随机取 pageSize 个
        List<Long> candidates = noteRankingService.getCandidateIds(pageSize, offset, windowSize);
        if (candidates.isEmpty()) return CursorPage.of(Collections.emptyList(), null, false, 0);

        // Step 2: 布隆过滤器过滤已曝光
        List<Long> fresh = new ArrayList<>();
        Long effectiveUserId = currentUserId != null ? currentUserId : 0L;
        for (Long nid : candidates) {
            if (!bloomFilterService.mightContain(effectiveUserId, nid)) {
                fresh.add(nid);
            }
            if (fresh.size() >= pageSize) break;
        }

        // Step 3: 标记已曝光
        if (currentUserId != null && !fresh.isEmpty()) {
            bloomFilterService.addAll(currentUserId, fresh);
        }

        // Step 4: 组装 VO，nextCursor 指向下一窗口起点
        List<Map<String, Object>> records = assembleNoteVOs(fresh, currentUserId);
        int nextOffset = offset + windowSize;
        long total = noteRankingService.getLatestTotal();
        boolean hasMore = nextOffset < total;
        return CursorPage.of(records, hasMore ? String.valueOf(nextOffset) : null, hasMore, records.size());
    }

    // ==================== 关注流（拉模式时序聚合） ====================

    public CursorPage<Map<String, Object>> followingFeed(Long currentUserId, Long cursor, int pageSize) {
        if (currentUserId == null) return CursorPage.of(Collections.emptyList(), null, false, 0);

        // Step 1: 获取关注的所有作者 ID
        CursorPage<Map<String, Object>> followingPage = redisFollowService.getFollowing(currentUserId, null, Integer.MAX_VALUE);
        List<Long> authorIds = followingPage.getRecords().stream()
                .map(m -> (Long) m.get("userId"))
                .collect(Collectors.toList());
        if (authorIds.isEmpty()) return CursorPage.of(Collections.emptyList(), null, false, 0);

        // Step 2: 批量获取所有关注作者的 note:mine ZSET（每人最多取 50 条）
        Set<Long> authorIdSet = new HashSet<>(authorIds);
        Map<Long, Long> allNoteScores = noteRankingService.batchGetAuthorNoteIds(authorIdSet, 50);
        if (allNoteScores.isEmpty()) return CursorPage.of(Collections.emptyList(), null, false, 0);

        // Step 3: 按时间戳降序排序
        List<Map.Entry<Long, Long>> sortedEntries = new ArrayList<>(allNoteScores.entrySet());
        sortedEntries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        // Step 4: 游标分页（cursor 为上一页最后一条的 score）
        long cursorVal = cursor != null ? cursor : Long.MAX_VALUE;
        List<Long> pageNoteIds = new ArrayList<>();
        double lastScore = 0;
        for (Map.Entry<Long, Long> entry : sortedEntries) {
            if (entry.getValue() < cursorVal) {
                pageNoteIds.add(entry.getKey());
                lastScore = entry.getValue();
                if (pageNoteIds.size() >= pageSize) break;
            }
        }

        // Step 5: 组装 VO
        List<Map<String, Object>> records = assembleNoteVOs(pageNoteIds, currentUserId);
        boolean hasMore = pageNoteIds.size() >= pageSize;
        return CursorPage.of(records, hasMore ? String.valueOf((long) lastScore) : null, hasMore, allNoteScores.size());
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

        // 同步写入 Redis ZSET
        noteRankingService.addNote(note);

        // RabbitMQ 异步通知
        Map<String, Object> msg = new HashMap<>();
        msg.put("noteId", note.getNoteId());
        msg.put("userId", userId);
        msg.put("createTime", toEpochMilli(note.getCreateTime()));
        rabbitTemplate.convertAndSend("school.ticket.exchange", "note.create", msg);

        return note;
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

    // ==================== 点赞/取消 ====================

    @Transactional
    public void like(Long noteId, Long userId) {
        Long count = noteLikeMapper.selectCount(
                new LambdaQueryWrapper<NoteLike>()
                        .eq(NoteLike::getNoteId, noteId)
                        .eq(NoteLike::getUserId, userId));
        if (count > 0) return;
        NoteLike nl = new NoteLike();
        nl.setNoteId(noteId);
        nl.setUserId(userId);
        noteLikeMapper.insert(nl);

        // 更新 Redis hottest ZSET
        long newCount = noteLikeMapper.selectCount(
                new LambdaQueryWrapper<NoteLike>().eq(NoteLike::getNoteId, noteId));
        noteRankingService.updateLikeCount(noteId, newCount);

        // RabbitMQ 异步
        Map<String, Object> msg = new HashMap<>();
        msg.put("action", "like");
        msg.put("noteId", noteId);
        msg.put("likeCount", newCount);
        rabbitTemplate.convertAndSend("school.ticket.exchange", "note.like", msg);
    }

    @Transactional
    public void unlike(Long noteId, Long userId) {
        noteLikeMapper.delete(
                new LambdaQueryWrapper<NoteLike>()
                        .eq(NoteLike::getNoteId, noteId)
                        .eq(NoteLike::getUserId, userId));

        noteRankingService.decrementLikeCount(noteId);

        Map<String, Object> msg = new HashMap<>();
        msg.put("action", "unlike");
        msg.put("noteId", noteId);
        msg.put("likeCount", noteLikeMapper.selectCount(
                new LambdaQueryWrapper<NoteLike>().eq(NoteLike::getNoteId, noteId)));
        rabbitTemplate.convertAndSend("school.ticket.exchange", "note.like", msg);
    }

    // ==================== 冷启动 ====================

    /** 从 MySQL 同步全部数据到 Redis */
    public void syncAllToRedis() {
        noteRankingService.syncAllFromDB();
        List<NoteLike> allLikes = noteLikeMapper.selectList(null);
        Map<Long, Long> likeCountMap = new HashMap<>();
        for (NoteLike lk : allLikes) {
            likeCountMap.merge(lk.getNoteId(), 1L, Long::sum);
        }
        noteRankingService.syncLikesFromDB(likeCountMap);
    }

    // ==================== VO 组装 ====================

    private List<Map<String, Object>> assembleNoteVOs(List<Long> noteIds, Long currentUserId) {
        if (noteIds.isEmpty()) return Collections.emptyList();

        List<Note> notes = noteMapper.selectBatchIds(noteIds);
        Map<Long, Note> noteMap = notes.stream().collect(Collectors.toMap(Note::getNoteId, n -> n));

        Set<Long> userIds = notes.stream().map(Note::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = new HashMap<>();
        if (!userIds.isEmpty())
            userMapper.selectBatchIds(userIds).forEach(u -> userMap.put(u.getUserId(), u));

        Map<Long, Long> likeCountMap = new HashMap<>();
        for (Long nid : noteIds) {
            Double score = noteRankingService.getNoteHottestScore(nid);
            if (score != null) likeCountMap.put(nid, score.longValue());
        }

        Set<Long> likedSet = Collections.emptySet();
        if (currentUserId != null) {
            likedSet = noteLikeMapper.selectList(
                    new LambdaQueryWrapper<NoteLike>()
                            .in(NoteLike::getNoteId, noteIds)
                            .eq(NoteLike::getUserId, currentUserId))
                    .stream()
                    .map(NoteLike::getNoteId)
                    .collect(Collectors.toSet());
        }

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
        }
        return records;
    }

    private static long toEpochMilli(LocalDateTime dt) {
        return dt.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }
}

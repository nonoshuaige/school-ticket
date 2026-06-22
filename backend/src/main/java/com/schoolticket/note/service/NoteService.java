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
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteMapper noteMapper;
    private final NoteLikeMapper noteLikeMapper;
    private final UserMapper userMapper;
    private final RedisNoteRankingService noteRankingService;
    private final RabbitTemplate rabbitTemplate;

    // ==================== 游标分页查询 ====================

    public CursorPage<Map<String, Object>> listNotes(Long cursor, int pageSize, Long currentUserId, String sort) {
        if ("hottest".equals(sort)) {
            return noteRankingService.getHottest(cursor, pageSize, currentUserId);
        }
        if ("mine".equals(sort)) {
            if (currentUserId == null)
                return CursorPage.of(Collections.emptyList(), null, false, 0);
            return noteRankingService.getMine(currentUserId, cursor, pageSize, currentUserId);
        }
        // latest
        return noteRankingService.getLatest(cursor, pageSize, currentUserId);
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

    private static long toEpochMilli(LocalDateTime dt) {
        return dt.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }
}

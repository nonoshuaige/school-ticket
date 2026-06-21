package com.schoolticket.note.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schoolticket.common.BusinessException;
import com.schoolticket.note.entity.Note;
import com.schoolticket.note.entity.NoteLike;
import com.schoolticket.note.mapper.NoteLikeMapper;
import com.schoolticket.note.mapper.NoteMapper;
import com.schoolticket.user.entity.User;
import com.schoolticket.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteMapper noteMapper;
    private final NoteLikeMapper noteLikeMapper;
    private final UserMapper userMapper;

    public IPage<Map<String, Object>> listNotes(Integer pageNum, Integer pageSize, Long currentUserId) {
        Page<Note> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Note> wrapper = new LambdaQueryWrapper<Note>()
                .orderByDesc(Note::getCreateTime);
        IPage<Note> notePage = noteMapper.selectPage(page, wrapper);

        List<Long> noteIds = notePage.getRecords().stream()
                .map(Note::getNoteId).collect(Collectors.toList());

        // 批量查点赞数
        Map<Long, Long> likeCountMap = new HashMap<>();
        if (!noteIds.isEmpty()) {
            List<NoteLike> allLikes = noteLikeMapper.selectList(
                    new LambdaQueryWrapper<NoteLike>().in(NoteLike::getNoteId, noteIds));
            likeCountMap = allLikes.stream()
                    .collect(Collectors.groupingBy(NoteLike::getNoteId, Collectors.counting()));
        }

        // 批量查当前用户已点赞的 noteId 集合
        Set<Long> likedNoteIds = new HashSet<>();
        if (currentUserId != null && !noteIds.isEmpty()) {
            likedNoteIds = noteLikeMapper.selectList(
                    new LambdaQueryWrapper<NoteLike>()
                            .eq(NoteLike::getUserId, currentUserId)
                            .in(NoteLike::getNoteId, noteIds))
                    .stream().map(NoteLike::getNoteId).collect(Collectors.toSet());
        }

        // 批量查用户信息
        Set<Long> userIds = new HashSet<>();
        notePage.getRecords().forEach(n -> userIds.add(n.getUserId()));
        Map<Long, User> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            userMapper.selectBatchIds(userIds).forEach(u -> userMap.put(u.getUserId(), u));
        }

        final Map<Long, Long> finalLikeCountMap = likeCountMap;
        final Set<Long> finalLikedNoteIds = likedNoteIds;

        return notePage.convert(note -> {
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("noteId", note.getNoteId());
            vo.put("content", note.getContent());
            vo.put("createTime", note.getCreateTime());
            vo.put("likeCount", finalLikeCountMap.getOrDefault(note.getNoteId(), 0L));
            vo.put("isLiked", finalLikedNoteIds.contains(note.getNoteId()));
            User author = userMap.get(note.getUserId());
            if (author != null) {
                vo.put("userId", author.getUserId());
                vo.put("nickname", author.getNickname());
                vo.put("phone", author.getPhone());
            }
            return vo;
        });
    }

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
    }

    @Transactional
    public void unlike(Long noteId, Long userId) {
        noteLikeMapper.delete(
                new LambdaQueryWrapper<NoteLike>()
                        .eq(NoteLike::getNoteId, noteId)
                        .eq(NoteLike::getUserId, userId));
    }
}

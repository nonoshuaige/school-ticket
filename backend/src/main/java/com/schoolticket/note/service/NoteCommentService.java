package com.schoolticket.note.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schoolticket.common.BusinessException;
import com.schoolticket.dto.CommentVO;
import com.schoolticket.note.entity.Note;
import com.schoolticket.note.entity.UserNoteComment;
import com.schoolticket.note.mapper.NoteMapper;
import com.schoolticket.note.mapper.UserNoteCommentMapper;
import com.schoolticket.user.entity.User;
import com.schoolticket.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NoteCommentService {

    private final UserNoteCommentMapper commentMapper;
    private final NoteMapper noteMapper;
    private final UserMapper userMapper;

    @Transactional
    public void createComment(Long noteId, Long userId, String content, Long parentId, Long replyToUid) {
        if (noteMapper.selectById(noteId) == null) {
            throw new BusinessException(400, "笔记不存在");
        }
        UserNoteComment comment = new UserNoteComment();
        comment.setNoteId(noteId);
        comment.setUserId(userId);
        comment.setContent(content);

        if (parentId != null && parentId > 0) {
            UserNoteComment parent = commentMapper.selectById(parentId);
            if (parent == null) {
                throw new BusinessException(400, "父评论不存在");
            }
            comment.setParentId(parentId);
            comment.setRootId(parent.getRootId() > 0 ? parent.getRootId() : parent.getCommentId());
            comment.setReplyToUid(replyToUid != null ? replyToUid : parent.getUserId());
        } else {
            comment.setParentId(0L);
            comment.setRootId(0L);
            comment.setReplyToUid(replyToUid);
        }
        commentMapper.insert(comment);
    }

    public IPage<CommentVO> listComments(Long noteId, Integer pageNum, Integer pageSize) {
        Page<UserNoteComment> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<UserNoteComment> wrapper = new LambdaQueryWrapper<UserNoteComment>()
                .eq(UserNoteComment::getNoteId, noteId)
                .eq(UserNoteComment::getRootId, 0L)
                .orderByDesc(UserNoteComment::getCreateTime);
        IPage<UserNoteComment> rootPage = commentMapper.selectPage(page, wrapper);

        List<Long> rootIds = rootPage.getRecords().stream()
                .map(UserNoteComment::getCommentId).collect(Collectors.toList());

        // 批量查子评论
        final Map<Long, List<UserNoteComment>> childrenMap;
        if (!rootIds.isEmpty()) {
            List<UserNoteComment> allChildren = commentMapper.selectList(
                    new LambdaQueryWrapper<UserNoteComment>()
                            .eq(UserNoteComment::getNoteId, noteId)
                            .in(UserNoteComment::getRootId, rootIds)
                            .orderByAsc(UserNoteComment::getCreateTime));
            childrenMap = allChildren.stream()
                    .collect(Collectors.groupingBy(UserNoteComment::getRootId));
        } else {
            childrenMap = Collections.emptyMap();
        }

        // 批量查用户信息
        Set<Long> allUserIds = new HashSet<>();
        rootPage.getRecords().forEach(c -> allUserIds.add(c.getUserId()));
        childrenMap.values().forEach(list -> list.forEach(c -> {
            allUserIds.add(c.getUserId());
            if (c.getReplyToUid() != null) allUserIds.add(c.getReplyToUid());
        }));
        Map<Long, User> userMap = new HashMap<>();
        if (!allUserIds.isEmpty()) {
            userMapper.selectBatchIds(allUserIds).forEach(u -> userMap.put(u.getUserId(), u));
        }

        return rootPage.convert(root -> {
            CommentVO vo = toVO(root, userMap);
            List<UserNoteComment> children = childrenMap.getOrDefault(root.getCommentId(), Collections.emptyList());
            vo.setChildren(children.stream().map(c -> toVO(c, userMap)).collect(Collectors.toList()));
            return vo;
        });
    }

    @Transactional
    public void deleteComment(Long commentId, Long userId) {
        UserNoteComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(400, "评论不存在");
        }
        if (!comment.getUserId().equals(userId)) {
            throw new BusinessException(403, "只能删除自己的评论");
        }
        if (comment.getRootId() == 0) {
            // 删除一级评论及其所有子评论
            commentMapper.delete(new LambdaQueryWrapper<UserNoteComment>()
                    .eq(UserNoteComment::getRootId, commentId));
            commentMapper.deleteById(commentId);
        } else {
            // 仅删除自身
            commentMapper.deleteById(commentId);
        }
    }

    private CommentVO toVO(UserNoteComment c, Map<Long, User> userMap) {
        CommentVO vo = new CommentVO();
        vo.setCommentId(c.getCommentId());
        vo.setNoteId(c.getNoteId());
        vo.setUserId(c.getUserId());
        vo.setContent(c.getContent());
        vo.setRootId(c.getRootId());
        vo.setParentId(c.getParentId());
        vo.setReplyToUid(c.getReplyToUid());
        vo.setCreateTime(c.getCreateTime());
        User u = userMap.get(c.getUserId());
        if (u != null) vo.setNickname(u.getNickname());
        if (c.getReplyToUid() != null) {
            User ru = userMap.get(c.getReplyToUid());
            if (ru != null) vo.setReplyToNickname(ru.getNickname());
        }
        return vo;
    }
}

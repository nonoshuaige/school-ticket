package com.schoolticket.note.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolticket.common.BusinessException;
import com.schoolticket.dto.CommentVO;
import com.schoolticket.note.entity.Note;
import com.schoolticket.note.entity.UserNoteComment;
import com.schoolticket.note.mapper.NoteMapper;
import com.schoolticket.note.mapper.UserNoteCommentMapper;
import com.schoolticket.user.entity.User;
import com.schoolticket.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NoteCommentService {

    private final UserNoteCommentMapper commentMapper;
    private final NoteMapper noteMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final String COMMENT_PAGE_KEY = "note:comments:page:%d:%d:%d";
    private static final String COMMENT_PAGE_INDEX_KEY = "note:comments:keys:%d";
    private static final String COMMENT_COUNT_KEY = "note:comment:count:%d";
    private static final int HOT_CACHE_MAX_PAGE = 3;
    private static final int HOT_CACHE_MAX_PAGE_SIZE = 20;
    private static final long COMMENT_PAGE_TTL_SECONDS = 300;
    private static final long COMMENT_COUNT_TTL_SECONDS = 7 * 24 * 3600;

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
        ensureCommentCountLoaded(noteId);
        commentMapper.insert(comment);
        incrCommentCount(noteId, 1);
        evictCommentPageCache(noteId);
    }

    public IPage<CommentVO> listComments(Long noteId, Integer pageNum, Integer pageSize) {
        IPage<CommentVO> cached = getCachedCommentPage(noteId, pageNum, pageSize);
        if (cached != null) {
            return cached;
        }

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

        IPage<CommentVO> result = rootPage.convert(root -> {
            CommentVO vo = toVO(root, userMap);
            List<UserNoteComment> children = childrenMap.getOrDefault(root.getCommentId(), Collections.emptyList());
            vo.setChildren(children.stream().map(c -> toVO(c, userMap)).collect(Collectors.toList()));
            return vo;
        });
        cacheCommentPage(noteId, pageNum, pageSize, result);
        ensureCommentCountLoaded(noteId);
        return result;
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
        ensureCommentCountLoaded(comment.getNoteId());
        long deletedCount = 1;
        if (comment.getRootId() == 0) {
            deletedCount += commentMapper.selectCount(new LambdaQueryWrapper<UserNoteComment>()
                    .eq(UserNoteComment::getRootId, commentId));
            // 删除一级评论及其所有子评论
            commentMapper.delete(new LambdaQueryWrapper<UserNoteComment>()
                    .eq(UserNoteComment::getRootId, commentId));
            commentMapper.deleteById(commentId);
        } else {
            // 仅删除自身
            commentMapper.deleteById(commentId);
        }
        incrCommentCount(comment.getNoteId(), -deletedCount);
        evictCommentPageCache(comment.getNoteId());
    }

    public long getCommentCount(Long noteId) {
        String key = String.format(COMMENT_COUNT_KEY, noteId);
        byte[] raw = redis.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
        if (raw != null) {
            try {
                String cached = new String(raw, StandardCharsets.UTF_8);
                if (cached.length() >= 2 && cached.startsWith("\"") && cached.endsWith("\"")) {
                    cached = cached.substring(1, cached.length() - 1);
                }
                return Long.parseLong(cached);
            } catch (NumberFormatException ignored) {
                redis.delete(key);
            }
        }
        return ensureCommentCountLoaded(noteId);
    }

    private long ensureCommentCountLoaded(Long noteId) {
        long count = commentMapper.selectCount(
                new LambdaQueryWrapper<UserNoteComment>().eq(UserNoteComment::getNoteId, noteId));
        setCommentCount(noteId, count);
        return count;
    }

    private void incrCommentCount(Long noteId, long delta) {
        String key = String.format(COMMENT_COUNT_KEY, noteId);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        try {
            Long count = redis.execute((RedisCallback<Long>) connection -> {
                Long incremented = connection.stringCommands().incrBy(keyBytes, delta);
                connection.keyCommands().expire(keyBytes, COMMENT_COUNT_TTL_SECONDS);
                return incremented;
            });
            if (count != null && count < 0) {
                setCommentCount(noteId, 0);
            }
        } catch (Exception e) {
            long actual = commentMapper.selectCount(
                    new LambdaQueryWrapper<UserNoteComment>().eq(UserNoteComment::getNoteId, noteId));
            setCommentCount(noteId, Math.max(0, actual));
        }
    }

    private void setCommentCount(Long noteId, long count) {
        byte[] keyBytes = String.format(COMMENT_COUNT_KEY, noteId).getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = String.valueOf(count).getBytes(StandardCharsets.UTF_8);
        redis.execute((RedisCallback<Void>) connection -> {
            connection.stringCommands().set(keyBytes, valueBytes);
            connection.keyCommands().expire(keyBytes, COMMENT_COUNT_TTL_SECONDS);
            return null;
        });
    }

    private IPage<CommentVO> getCachedCommentPage(Long noteId, Integer pageNum, Integer pageSize) {
        if (!isHotCommentPage(pageNum, pageSize)) return null;
        String key = String.format(COMMENT_PAGE_KEY, noteId, pageNum, pageSize);
        String cached = redis.opsForValue().get(key);
        if (cached == null) return null;
        try {
            return objectMapper.readValue(cached, new TypeReference<Page<CommentVO>>() {});
        } catch (Exception e) {
            log.warn("Comment page cache decode failed, key={}", key, e);
            redis.delete(key);
            return null;
        }
    }

    private void cacheCommentPage(Long noteId, Integer pageNum, Integer pageSize, IPage<CommentVO> page) {
        if (!isHotCommentPage(pageNum, pageSize)) return;
        String key = String.format(COMMENT_PAGE_KEY, noteId, pageNum, pageSize);
        String indexKey = String.format(COMMENT_PAGE_INDEX_KEY, noteId);
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(page),
                    COMMENT_PAGE_TTL_SECONDS, TimeUnit.SECONDS);
            redis.opsForSet().add(indexKey, key);
            redis.expire(indexKey, COMMENT_PAGE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Comment page cache encode failed, key={}", key, e);
        }
    }

    private void evictCommentPageCache(Long noteId) {
        String indexKey = String.format(COMMENT_PAGE_INDEX_KEY, noteId);
        Set<String> keys = redis.opsForSet().members(indexKey);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(indexKey);
    }

    private boolean isHotCommentPage(Integer pageNum, Integer pageSize) {
        return pageNum != null && pageSize != null
                && pageNum >= 1 && pageNum <= HOT_CACHE_MAX_PAGE
                && pageSize > 0 && pageSize <= HOT_CACHE_MAX_PAGE_SIZE;
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

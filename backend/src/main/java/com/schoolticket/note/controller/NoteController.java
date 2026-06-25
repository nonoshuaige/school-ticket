package com.schoolticket.note.controller;

import com.schoolticket.common.BusinessException;
import com.schoolticket.common.CurrentUserHolder;
import com.schoolticket.common.Result;
import com.schoolticket.note.service.NoteCommentService;
import com.schoolticket.note.service.NoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/note")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;
    private final NoteCommentService noteCommentService;

    /** 推荐流 - 布隆过滤器消重 + 游标翻页 */
    @GetMapping("/recommend-feed")
    public Result<?> recommendFeed(@RequestParam(required = false) Long cursor,
                                   @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = tryGetUserId();
        return Result.success(noteService.recommendFeed(userId, cursor, pageSize));
    }

    /** 关注流 - 拉模式时序聚合 */
    @GetMapping("/following-feed")
    public Result<?> followingFeed(@RequestParam(required = false) Long cursor,
                                   @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = tryGetUserId();
        return Result.success(noteService.followingFeed(userId, cursor, pageSize));
    }

    /** 我的笔记 - 独立分页（需登录） */
    @GetMapping("/my-notes")
    public Result<?> myNotes(@RequestParam(required = false) Long cursor,
                             @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = requireUserId();
        return Result.success(noteService.myNotes(userId, cursor, pageSize));
    }

    /** 冷启动：同步 MySQL → Redis */
    @PostMapping("/sync-to-redis")
    public Result<?> syncToRedis() {
        noteService.syncAllToRedis();
        return Result.success("ok");
    }

    @PostMapping("/create")
    public Result<?> create(@RequestBody Map<String, Object> body) {
        Long userId = requireUserId();
        String content = (String) body.get("content");
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(400, "内容不能为空");
        }
        if (content.length() > 500) {
            throw new BusinessException(400, "内容不能超过500字");
        }
        return Result.success(noteService.createNote(userId, content.trim()));
    }

    @GetMapping("/{noteId}")
    public Result<?> detail(@PathVariable Long noteId) {
        Long userId = tryGetUserId();
        return Result.success(noteService.getNoteDetail(noteId, userId));
    }

    @PostMapping("/{noteId}/like")
    public Result<?> like(@PathVariable Long noteId) {
        Long userId = requireUserId();
        noteService.like(noteId, userId);
        return Result.success(null);
    }

    @DeleteMapping("/{noteId}/like")
    public Result<?> unlike(@PathVariable Long noteId) {
        Long userId = requireUserId();
        noteService.unlike(noteId, userId);
        return Result.success(null);
    }

    @PostMapping("/{noteId}/comment")
    public Result<?> createComment(@PathVariable Long noteId, @RequestBody Map<String, Object> body) {
        Long userId = requireUserId();
        String content = (String) body.get("content");
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(400, "评论内容不能为空");
        }
        if (content.length() > 500) {
            throw new BusinessException(400, "评论内容不能超过500字");
        }
        Long parentId = body.get("parentId") != null ? ((Number) body.get("parentId")).longValue() : 0L;
        Long replyToUid = body.get("replyToUid") != null ? ((Number) body.get("replyToUid")).longValue() : null;
        noteCommentService.createComment(noteId, userId, content.trim(), parentId, replyToUid);
        return Result.success(null);
    }

    @GetMapping("/{noteId}/comment")
    public Result<?> listComments(@PathVariable Long noteId,
                                  @RequestParam(defaultValue = "1") Integer page,
                                  @RequestParam(defaultValue = "20") Integer pageSize) {
        return Result.success(noteCommentService.listComments(noteId, page, pageSize));
    }

    @DeleteMapping("/{noteId}")
    public Result<?> deleteNote(@PathVariable Long noteId) {
        Long userId = requireUserId();
        noteService.deleteNote(noteId, userId);
        return Result.success(null);
    }

    @DeleteMapping("/{noteId}/comment/{commentId}")
    public Result<?> deleteComment(@PathVariable Long noteId, @PathVariable Long commentId) {
        Long userId = requireUserId();
        noteCommentService.deleteComment(commentId, userId);
        return Result.success(null);
    }

    private Long requireUserId() {
        Long userId = tryGetUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        return userId;
    }

    private Long tryGetUserId() {
        try {
            return CurrentUserHolder.getUserId();
        } catch (Exception e) {
            return null;
        }
    }
}

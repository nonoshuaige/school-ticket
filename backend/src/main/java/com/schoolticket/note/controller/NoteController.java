package com.schoolticket.note.controller;

import com.schoolticket.common.BusinessException;
import com.schoolticket.common.CurrentUserHolder;
import com.schoolticket.common.Result;
import com.schoolticket.note.service.NoteCommentService;
import com.schoolticket.note.service.NoteService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/note")
@RequiredArgsConstructor
public class NoteController {

    private static final String ANON_FEED_COOKIE = "feed_session";

    private final NoteService noteService;
    private final NoteCommentService noteCommentService;

    /** 推荐流 - 布隆过滤器消重 + 游标翻页 */
    @GetMapping("/recommend-feed")
    public Result<?> recommendFeed(@RequestParam(required = false) Long cursor,
                                   @RequestParam(defaultValue = "10") Integer pageSize,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        Long userId = tryGetUserId();
        String anonymousFeedId = userId == null ? resolveAnonymousFeedId(request, response) : null;
        return Result.success(noteService.recommendFeed(userId, anonymousFeedId, cursor, normalizePageSize(pageSize)));
    }

    /** 关注流 - 拉模式时序聚合 */
    @GetMapping("/following-feed")
    public Result<?> followingFeed(@RequestParam(required = false) String cursor,
                                   @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = tryGetUserId();
        return Result.success(noteService.followingFeed(userId, cursor, normalizePageSize(pageSize)));
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
        requireUserId();
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
        // 解析可选 eventIds
        List<Long> eventIds = null;
        Object eidsObj = body.get("eventIds");
        if (eidsObj instanceof List<?> list && !list.isEmpty()) {
            eventIds = list.stream()
                    .map(o -> ((Number) o).longValue())
                    .collect(Collectors.toList());
        }
        return Result.success(noteService.createNote(userId, content.trim(), eventIds));
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

    @GetMapping("/{noteId}/comment/count")
    public Result<?> commentCount(@PathVariable Long noteId) {
        return Result.success(noteCommentService.getCommentCount(noteId));
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

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null) return 10;
        return Math.max(1, Math.min(pageSize, 50));
    }

    private String resolveAnonymousFeedId(HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (ANON_FEED_COOKIE.equals(cookie.getName())
                        && cookie.getValue() != null
                        && cookie.getValue().matches("[a-f0-9-]{36}")) {
                    return cookie.getValue();
                }
            }
        }
        String id = UUID.randomUUID().toString();
        response.addHeader("Set-Cookie", ANON_FEED_COOKIE + "=" + id
                + "; Path=/; Max-Age=1800; HttpOnly; SameSite=Lax");
        return id;
    }
}

package com.schoolticket.note.controller;

import com.schoolticket.common.BusinessException;
import com.schoolticket.common.CurrentUserHolder;
import com.schoolticket.common.Result;
import com.schoolticket.note.service.NoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/note")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;

    @GetMapping("/list")
    public Result<?> list(@RequestParam(defaultValue = "1") Integer page,
                          @RequestParam(defaultValue = "20") Integer pageSize) {
        Long userId = tryGetUserId();
        return Result.success(noteService.listNotes(page, pageSize, userId));
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

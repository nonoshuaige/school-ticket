package com.schoolticket.user.controller;

import com.schoolticket.common.CurrentUserHolder;
import com.schoolticket.common.Result;
import com.schoolticket.user.service.UserFollowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user/follow")
@RequiredArgsConstructor
public class UserFollowController {

    private final UserFollowService userFollowService;

    @PostMapping("/{followeeId}")
    public Result<?> follow(@PathVariable Long followeeId) {
        Long userId = CurrentUserHolder.getUserId();
        userFollowService.follow(userId, followeeId);
        return Result.success(null);
    }

    @DeleteMapping("/{followeeId}")
    public Result<?> unfollow(@PathVariable Long followeeId) {
        Long userId = CurrentUserHolder.getUserId();
        userFollowService.unfollow(userId, followeeId);
        return Result.success(null);
    }

    @GetMapping("/stats")
    public Result<?> stats() {
        Long userId = CurrentUserHolder.getUserId();
        return Result.success(userFollowService.getFollowStats(userId));
    }

    @GetMapping("/check/{followeeId}")
    public Result<?> check(@PathVariable Long followeeId) {
        Long userId = CurrentUserHolder.getUserId();
        return Result.success(userFollowService.isFollowing(userId, followeeId));
    }
}

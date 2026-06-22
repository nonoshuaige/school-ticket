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

    @PostMapping("/{userId}")
    public Result<?> follow(@PathVariable Long userId) {
        Long currentUserId = CurrentUserHolder.getUserId();
        userFollowService.follow(currentUserId, userId);
        return Result.success(null);
    }

    @DeleteMapping("/{userId}")
    public Result<?> unfollow(@PathVariable Long userId) {
        Long currentUserId = CurrentUserHolder.getUserId();
        userFollowService.unfollow(currentUserId, userId);
        return Result.success(null);
    }

    @GetMapping("/stats")
    public Result<?> stats() {
        Long currentUserId = CurrentUserHolder.getUserId();
        return Result.success(userFollowService.getFollowStats(currentUserId));
    }

    @GetMapping("/check/{userId}")
    public Result<?> check(@PathVariable Long userId) {
        Long currentUserId = CurrentUserHolder.getUserId();
        return Result.success(userFollowService.isFollowing(currentUserId, userId));
    }

    /** 我的关注列表 - 游标分页 */
    @GetMapping("/following")
    public Result<?> following(@RequestParam(required = false) Long cursor,
                               @RequestParam(defaultValue = "20") Integer pageSize) {
        Long currentUserId = CurrentUserHolder.getUserId();
        return Result.success(userFollowService.getFollowingList(currentUserId, cursor, pageSize));
    }

    /** 我的粉丝列表 - 游标分页 */
    @GetMapping("/fans")
    public Result<?> fans(@RequestParam(required = false) Long cursor,
                          @RequestParam(defaultValue = "20") Integer pageSize) {
        Long currentUserId = CurrentUserHolder.getUserId();
        return Result.success(userFollowService.getFansList(currentUserId, cursor, pageSize));
    }

    /** 冷启动：同步关注关系到 Redis */
    @PostMapping("/sync-to-redis")
    public Result<?> syncToRedis() {
        userFollowService.syncFollowToRedis();
        return Result.success("ok");
    }
}

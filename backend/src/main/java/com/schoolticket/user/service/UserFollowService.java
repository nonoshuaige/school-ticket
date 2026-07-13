package com.schoolticket.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schoolticket.common.BusinessException;
import com.schoolticket.config.RabbitMQConfig;
import com.schoolticket.dto.CursorPage;
import com.schoolticket.note.service.RedisNoteRankingService;
import com.schoolticket.note.service.FeedEventPublisher;
import com.schoolticket.user.entity.UserFollow;
import com.schoolticket.user.entity.User;
import com.schoolticket.user.mapper.UserFollowMapper;
import com.schoolticket.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserFollowService {

    private final UserFollowMapper userFollowMapper;
    private final UserMapper userMapper;
    private final RedisFollowService redisFollowService;
    private final RedisNoteRankingService noteRankingService;
    private final FeedEventPublisher feedEventPublisher;

    @Value("${feed.middle-v-enter-threshold:1000}")
    private int middleVEnterThreshold;

    @Value("${feed.middle-v-exit-threshold:800}")
    private int middleVExitThreshold;

    @Value("${feed.big-v-enter-threshold:10000}")
    private int bigVEnterThreshold;

    @Value("${feed.big-v-exit-threshold:8000}")
    private int bigVExitThreshold;

    @Transactional
    public void follow(Long followerId, Long userId) {
        if (followerId.equals(userId)) {
            throw new BusinessException(BusinessException.CANNOT_FOLLOW_SELF, "不能关注自己");
        }
        Long count = userFollowMapper.selectCount(
                new LambdaQueryWrapper<UserFollow>()
                        .eq(UserFollow::getFollowerId, followerId)
                        .eq(UserFollow::getUserId, userId));
        if (count > 0) {
            throw new BusinessException(BusinessException.ALREADY_FOLLOWING, "已关注该用户");
        }
        UserFollow uf = new UserFollow();
        uf.setFollowerId(followerId);
        uf.setUserId(userId);
        userFollowMapper.insert(uf);

        afterCommit(() -> syncFollowChange("follow", followerId, userId));
    }

    @Transactional
    public void unfollow(Long followerId, Long userId) {
        userFollowMapper.delete(
                new LambdaQueryWrapper<UserFollow>()
                        .eq(UserFollow::getFollowerId, followerId)
                        .eq(UserFollow::getUserId, userId));

        afterCommit(() -> syncFollowChange("unfollow", followerId, userId));
    }

    private void syncFollowChange(String action, Long followerId, Long userId) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("action", action);
        msg.put("followerId", followerId);
        msg.put("userId", userId);
        runSafely("publish user.follow", () ->
                feedEventPublisher.publish(RabbitMQConfig.RK_FOLLOW, msg));

        runSafely("sync follow cache", () -> {
            if ("follow".equals(action)) {
                redisFollowService.addFollow(followerId, userId);
                noteRankingService.markUserActive(followerId, Collections.singleton(userId));
                RedisNoteRankingService.AuthorFanoutTier tier = resolveAuthorFanoutTier(
                        userId, redisFollowService.getFansCount(userId));
                if (tier != RedisNoteRankingService.AuthorFanoutTier.BIG) {
                    noteRankingService.backfillInboxForNewFan(followerId, userId);
                }
            } else {
                redisFollowService.removeFollow(followerId, userId);
                noteRankingService.removeActiveFan(userId, followerId);
                resolveAuthorFanoutTier(userId, redisFollowService.getFansCount(userId));
                noteRankingService.removeAuthorFromInbox(followerId, userId);
            }
        });
    }

    public boolean isFollowing(Long followerId, Long userId) {
        // 先查 Redis，再查 MySQL
        if (redisFollowService.isFollowing(followerId, userId)) return true;
        return userFollowMapper.selectCount(
                new LambdaQueryWrapper<UserFollow>()
                        .eq(UserFollow::getFollowerId, followerId)
                        .eq(UserFollow::getUserId, userId)) > 0;
    }

    public Map<Long, Boolean> batchCheckFollowing(Long followerId, List<Long> userIds) {
        Map<Long, Boolean> result = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty()) return result;

        List<Long> distinctIds = userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (distinctIds.isEmpty()) return result;

        redisFollowService.ensureFollowLoaded(followerId);
        Map<Long, Long> followed = redisFollowService.getFollowTimes(followerId, new LinkedHashSet<>(distinctIds));
        for (Long userId : distinctIds) {
            result.put(userId, followed.containsKey(userId));
        }
        return result;
    }

    public Map<String, Object> getFollowStats(Long userId) {
        long followingCount = redisFollowService.getFollowCount(userId);
        long followerCount  = redisFollowService.getFansCount(userId);
        if (followingCount == 0 && followerCount == 0) {
            // fallback to MySQL
            followingCount = userFollowMapper.selectCount(
                    new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowerId, userId));
            followerCount = userFollowMapper.selectCount(
                    new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getUserId, userId));
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("followingCount", followingCount);
        stats.put("followerCount", followerCount);
        return stats;
    }

    /** 游标分页查询我的关注列表 */
    public CursorPage<Map<String, Object>> getFollowingList(Long userId, Long cursor, int pageSize) {
        redisFollowService.ensureFollowLoaded(userId);
        CursorPage<Map<String, Object>> page = redisFollowService.getFollowing(userId, cursor, pageSize);
        if (page.getRecords().isEmpty()) return page;

        // 批量查用户信息
        Set<Long> userIds = page.getRecords().stream()
                .map(m -> (Long) m.get("userId")).collect(Collectors.toSet());
        Map<Long, User> userMap = new HashMap<>();
        userMapper.selectBatchIds(userIds).forEach(u -> userMap.put(u.getUserId(), u));

        for (Map<String, Object> m : page.getRecords()) {
            Long uid = (Long) m.get("userId");
            User u = userMap.get(uid);
            if (u != null) {
                m.put("nickname", u.getNickname());
                m.put("phone", u.getPhone());
            }
            m.put("isFollowing", true); // 自己的关注列表，必定已关注
        }
        return page;
    }

    /** 游标分页查询我的粉丝列表 */
    public CursorPage<Map<String, Object>> getFansList(Long userId, Long cursor, int pageSize) {
        redisFollowService.ensureFansLoaded(userId);
        CursorPage<Map<String, Object>> page = redisFollowService.getFans(userId, cursor, pageSize);
        if (page.getRecords().isEmpty()) return page;

        Set<Long> userIds = page.getRecords().stream()
                .map(m -> (Long) m.get("userId")).collect(Collectors.toSet());
        Map<Long, User> userMap = new HashMap<>();
        userMapper.selectBatchIds(userIds).forEach(u -> userMap.put(u.getUserId(), u));

        // 查我是否关注了这些粉丝
        Map<Long, Long> followTimes = redisFollowService.getFollowTimes(userId, userIds);

        for (Map<String, Object> m : page.getRecords()) {
            Long uid = (Long) m.get("userId");
            User u = userMap.get(uid);
            if (u != null) {
                m.put("nickname", u.getNickname());
                m.put("phone", u.getPhone());
            }
            m.put("isFollowing", followTimes.containsKey(uid));
        }
        return page;
    }

    /** 冷启动：从 MySQL 同步当前用户的关注关系到 Redis */
    public void syncFollowToRedis(Long userId) {
        redisFollowService.ensureFollowLoaded(userId);
        redisFollowService.ensureFansLoaded(userId);
    }

    private RedisNoteRankingService.AuthorFanoutTier resolveAuthorFanoutTier(Long userId, long fanCount) {
        return noteRankingService.resolveAuthorFanoutTier(
                userId,
                fanCount,
                middleVEnterThreshold,
                middleVExitThreshold,
                bigVEnterThreshold,
                bigVExitThreshold);
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void runSafely(String actionName, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("Feed after-commit action failed: {}", actionName, e);
        }
    }
}

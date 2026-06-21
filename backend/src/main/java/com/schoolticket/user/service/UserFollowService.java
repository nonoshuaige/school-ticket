package com.schoolticket.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schoolticket.common.BusinessException;
import com.schoolticket.user.entity.UserFollow;
import com.schoolticket.user.mapper.UserFollowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserFollowService {

    private final UserFollowMapper userFollowMapper;

    public void follow(Long followerId, Long followeeId) {
        if (followerId.equals(followeeId)) {
            throw new BusinessException(BusinessException.CANNOT_FOLLOW_SELF, "不能关注自己");
        }
        Long count = userFollowMapper.selectCount(
                new LambdaQueryWrapper<UserFollow>()
                        .eq(UserFollow::getFollowerId, followerId)
                        .eq(UserFollow::getFolloweeId, followeeId));
        if (count > 0) {
            throw new BusinessException(BusinessException.ALREADY_FOLLOWING, "已关注该用户");
        }
        UserFollow uf = new UserFollow();
        uf.setFollowerId(followerId);
        uf.setFolloweeId(followeeId);
        userFollowMapper.insert(uf);
    }

    public void unfollow(Long followerId, Long followeeId) {
        userFollowMapper.delete(
                new LambdaQueryWrapper<UserFollow>()
                        .eq(UserFollow::getFollowerId, followerId)
                        .eq(UserFollow::getFolloweeId, followeeId));
    }

    public boolean isFollowing(Long followerId, Long followeeId) {
        return userFollowMapper.selectCount(
                new LambdaQueryWrapper<UserFollow>()
                        .eq(UserFollow::getFollowerId, followerId)
                        .eq(UserFollow::getFolloweeId, followeeId)) > 0;
    }

    public Map<String, Long> getFollowStats(Long userId) {
        long followingCount = userFollowMapper.selectCount(
                new LambdaQueryWrapper<UserFollow>()
                        .eq(UserFollow::getFollowerId, userId));
        long followerCount = userFollowMapper.selectCount(
                new LambdaQueryWrapper<UserFollow>()
                        .eq(UserFollow::getFolloweeId, userId));
        return Map.of("followingCount", followingCount, "followerCount", followerCount);
    }
}

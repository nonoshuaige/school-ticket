package com.schoolticket.user.service;

import com.schoolticket.dto.CursorPage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RedisFollowService {

    private final StringRedisTemplate redis;

    private static final String FOLLOW_KEY = "user:follow:%d";
    private static final String FANS_KEY  = "user:fans:%d";

    // ==================== 写操作 ====================

    public void addFollow(Long followerId, Long userId) {
        long now = System.currentTimeMillis();
        String key = String.format(FOLLOW_KEY, followerId);
        redis.opsForZSet().add(key, String.valueOf(userId), now);
        String fansKey = String.format(FANS_KEY, userId);
        redis.opsForZSet().add(fansKey, String.valueOf(followerId), now);
    }

    public void removeFollow(Long followerId, Long userId) {
        String key = String.format(FOLLOW_KEY, followerId);
        redis.opsForZSet().remove(key, String.valueOf(userId));
        String fansKey = String.format(FANS_KEY, userId);
        redis.opsForZSet().remove(fansKey, String.valueOf(followerId));
    }

    public boolean isFollowing(Long followerId, Long userId) {
        Double score = redis.opsForZSet().score(String.format(FOLLOW_KEY, followerId), String.valueOf(userId));
        return score != null;
    }

    // ==================== 游标分页查询 ====================

    /** 查询我关注的人，按关注时间倒序 */
    public CursorPage<Map<String, Object>> getFollowing(Long userId, Long cursor, int pageSize) {
        String key = String.format(FOLLOW_KEY, userId);
        return queryZSetPage(key, cursor, pageSize);
    }

    /** 查询我的粉丝，按关注时间倒序 */
    public CursorPage<Map<String, Object>> getFans(Long userId, Long cursor, int pageSize) {
        String key = String.format(FANS_KEY, userId);
        return queryZSetPage(key, cursor, pageSize);
    }

    public long getFollowCount(Long userId) {
        Long size = redis.opsForZSet().zCard(String.format(FOLLOW_KEY, userId));
        return size == null ? 0 : size;
    }

    public long getFansCount(Long userId) {
        Long size = redis.opsForZSet().zCard(String.format(FANS_KEY, userId));
        return size == null ? 0 : size;
    }

    /** 获取全部粉丝 ID 集合（fanout 用） */
    public Set<Long> getFanIds(Long userId) {
        Set<String> members = redis.opsForZSet().range(String.format(FANS_KEY, userId), 0, -1);
        if (members == null || members.isEmpty()) return Collections.emptySet();
        return members.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    /** 批量获取关注时间（返回 followeeId → followTime） */
    public Map<Long, Long> getFollowTimes(Long followerId, Set<Long> userIds) {
        Map<Long, Long> result = new HashMap<>();
        String key = String.format(FOLLOW_KEY, followerId);
        for (Long uid : userIds) {
            Double score = redis.opsForZSet().score(key, String.valueOf(uid));
            if (score != null) result.put(uid, score.longValue());
        }
        return result;
    }

    // ==================== 内部 ====================

    private CursorPage<Map<String, Object>> queryZSetPage(String key, Long cursor, int pageSize) {
        long total = Optional.ofNullable(redis.opsForZSet().zCard(key)).orElse(0L);
        if (total == 0) return CursorPage.of(Collections.emptyList(), null, false, 0);

        long max = cursor != null ? cursor - 1 : Long.MAX_VALUE;
        long min = 0;

        Set<String> members = redis.opsForZSet().reverseRangeByScore(key, min, max, 0, pageSize);
        if (members == null || members.isEmpty())
            return CursorPage.of(Collections.emptyList(), null, false, total);

        List<Map<String, Object>> records = new ArrayList<>();
        double lastScore = 0;
        for (String member : members) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", Long.valueOf(member));
            Double score = redis.opsForZSet().score(key, member);
            map.put("followTime", score != null ? score.longValue() : 0);
            records.add(map);
            if (score != null) lastScore = score;
        }

        long nextCursor = (long) lastScore;
        boolean hasMore = total > (cursor != null ? redis.opsForZSet().reverseRangeByScore(key, min, max, 0, (int) Math.min(total, Integer.MAX_VALUE)).size() : records.size());
        // simplify: if we got a full page, there might be more
        hasMore = records.size() >= pageSize;

        return CursorPage.of(records, hasMore ? String.valueOf(nextCursor) : null, hasMore, total);
    }
}

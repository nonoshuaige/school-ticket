package com.schoolticket.order.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户级请求频率限制：每人每秒最多 1 次抢购请求。
 * 替代原 Redis dedup key，在应用层快速失败。
 */
@Component
public class RateLimiter {

    private final ConcurrentHashMap<Long, Long> lastRequest = new ConcurrentHashMap<>();

    /**
     * @return true=被限流，false=通过
     */
    public boolean isRateLimited(Long userId) {
        long now = System.currentTimeMillis();
        Long prev = lastRequest.put(userId, now);
        return prev != null && (now - prev) < 1000;
    }
}

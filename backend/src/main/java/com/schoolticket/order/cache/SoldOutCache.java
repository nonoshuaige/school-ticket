package com.schoolticket.order.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 本地售罄缓存：Caffeine 5秒过期，避免无效请求穿透到 Redis/DB。
 * 只存 soldOut=true 的 ticketId，未命中 = 未售罄。
 */
@Component
public class SoldOutCache {

    private final Cache<Long, Boolean> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .maximumSize(1000)
            .build();

    public boolean isSoldOut(Long ticketId) {
        return Boolean.TRUE.equals(cache.getIfPresent(ticketId));
    }

    public void markSoldOut(Long ticketId) {
        cache.put(ticketId, Boolean.TRUE);
    }

    public void invalidate(Long ticketId) {
        cache.invalidate(ticketId);
    }
}

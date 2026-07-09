package com.schoolticket.order.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local sold-out cache.
 *
 * Only soldOut=true is cached. When cache misses, one request per ticket checks
 * the Redis sold-out flag and fills Caffeine. Competing requests briefly recheck
 * the local cache and then continue to the normal Redis precheck/Lua path if no
 * sold-out flag has been filled. This preserves sold-out short-circuiting
 * without rejecting valid high-concurrency requests before stock is exhausted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SoldOutCache {

    private final StringRedisTemplate redis;

    private final Cache<Long, Boolean> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .maximumSize(1000)
            .build();

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    private static final String SOLDOUT_KEY = "ticket:soldout:%d";

    public boolean isSoldOut(Long ticketId) {
        return Boolean.TRUE.equals(cache.getIfPresent(ticketId));
    }

    public void markSoldOut(Long ticketId) {
        cache.put(ticketId, Boolean.TRUE);
    }

    public boolean checkSoldOut(Long ticketId) {
        Boolean cached = cache.getIfPresent(ticketId);
        if (Boolean.TRUE.equals(cached)) {
            return true;
        }

        ReentrantLock lock = locks.computeIfAbsent(ticketId, k -> new ReentrantLock());

        if (lock.tryLock()) {
            try {
                cached = cache.getIfPresent(ticketId);
                if (Boolean.TRUE.equals(cached)) {
                    return true;
                }

                String soldoutFlag = redis.opsForValue()
                        .get(String.format(SOLDOUT_KEY, ticketId));
                boolean soldOut = "1".equals(soldoutFlag);
                if (soldOut) {
                    markSoldOut(ticketId);
                }
                return soldOut;
            } catch (Exception e) {
                log.error("Sold-out Redis recheck failed, ticketId={}", ticketId, e);
                return false;
            } finally {
                lock.unlock();
                locks.remove(ticketId, lock);
            }
        }

        try {
            TimeUnit.MILLISECONDS.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Boolean.TRUE.equals(cache.getIfPresent(ticketId));
    }
}

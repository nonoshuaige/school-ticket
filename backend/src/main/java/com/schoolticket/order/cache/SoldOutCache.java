package com.schoolticket.order.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 本地售罄缓存：Caffeine 5秒过期 + 单飞锁防击穿。
 * 只存 soldOut=true 的 ticketId，未命中 = 未售罄。
 *
 * 单飞锁：Caffeine 未命中时，同一 ticketId 只有一个线程查询 Redis，
 * 其余线程等待结果，避免高并发下大量请求击穿到 Redis。
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

    /** per-ticket 锁对象池，用于单飞锁 */
    private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();

    private static final String SOLDOUT_KEY = "ticket:soldout:%d";

    public boolean isSoldOut(Long ticketId) {
        return Boolean.TRUE.equals(cache.getIfPresent(ticketId));
    }

    public void markSoldOut(Long ticketId) {
        cache.put(ticketId, Boolean.TRUE);
    }

    /**
     * 单飞锁检查售罄状态。
     * Caffeine 命中 → 直接返回；未命中 → per-key 锁 → 查询 Redis → 更新 Caffeine。
     *
     * @param ticketId 票档ID
     * @return true=已售罄, false=有库存
     */
    public boolean checkSoldOut(Long ticketId) {
        // 快速路径：Caffeine 命中
        Boolean cached = cache.getIfPresent(ticketId);
        if (Boolean.TRUE.equals(cached)) {
            return true;
        }

        Object lock = locks.computeIfAbsent(ticketId, k -> new Object());
        synchronized (lock) {
            try {
                // 双重检查：可能前一个线程已更新了 Caffeine
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
                log.error("单飞锁查Redis售罄异常 ticketId={}", ticketId, e);
                return false;
            } finally {
                locks.remove(ticketId, lock);
            }
        }
    }
}

package com.schoolticket.order.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.schoolticket.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地售罄缓存：Caffeine 5秒过期 + 单飞锁防击穿。
 * 只存 soldOut=true 的 ticketId，未命中 = 未售罄。
 *
 * 单飞锁：tryLock 抢锁，抢到的查 Redis 并写入 Caffeine，
 * 没抢到的直接快速失败，避免热点窗口大量请求占住业务线程。
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
        // 快速路径：Caffeine 命中
        Boolean cached = cache.getIfPresent(ticketId);
        if (Boolean.TRUE.equals(cached)) {
            return true;
        }

        ReentrantLock lock = locks.computeIfAbsent(ticketId, k -> new ReentrantLock());

        if (lock.tryLock()) {
            try {
                // 拿到锁后双重检查：可能 Caffeine 已被写入（极少情况）
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
                lock.unlock();
                locks.remove(ticketId, lock);
            }
        } else {
            // 没抢到锁说明同票档已有线程在回查 Redis。直接快速失败，保护后续 Lua/队列链路。
            throw new BusinessException("请求过于火爆，请稍后重试");
        }
    }
}

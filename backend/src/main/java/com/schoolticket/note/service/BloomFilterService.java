package com.schoolticket.note.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis BitMap 的分布式布隆过滤器 —— 笔记曝光消重
 * Key: user:bloom:seen:{userId}
 * 参数: n≈10000, p≈0.01 → m=95850 bits, k=7
 */
@Service
@RequiredArgsConstructor
public class BloomFilterService {

    private final StringRedisTemplate redis;

    private static final String KEY_PREFIX = "user:bloom:seen:";
    private static final int BIT_SIZE = 95850;
    private static final int HASH_COUNT = 7;
    private static final long TTL_SECONDS = 7 * 24 * 3600; // 7天过期

    /** 检查 noteId 是否可能已被该用户看过 */
    public boolean mightContain(Long userId, Long noteId) {
        String key = KEY_PREFIX + userId;
        byte[] raw = String.valueOf(noteId).getBytes(StandardCharsets.UTF_8);
        long[] offsets = hash(raw);
        for (long offset : offsets) {
            Boolean bit = redis.opsForValue().getBit(key, offset);
            if (bit == null || !bit) return false;
        }
        return true;
    }

    /** 标记 noteId 已被该用户看过 */
    public void add(Long userId, Long noteId) {
        String key = KEY_PREFIX + userId;
        byte[] raw = String.valueOf(noteId).getBytes(StandardCharsets.UTF_8);
        long[] offsets = hash(raw);
        for (long offset : offsets) {
            redis.opsForValue().setBit(key, offset, true);
        }
        redis.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
    }

    /** 批量标记 */
    public void addAll(Long userId, java.util.List<Long> noteIds) {
        for (Long nid : noteIds) {
            add(userId, nid);
        }
    }

    private long[] hash(byte[] raw) {
        long h1 = fnv1a64(raw);
        long h2 = mur3_64(raw, 0x165667b19L);
        long[] offsets = new long[HASH_COUNT];
        for (int i = 0; i < HASH_COUNT; i++) {
            long combined = h1 + (long) i * h2;
            offsets[i] = Math.abs(combined % BIT_SIZE);
        }
        return offsets;
    }

    private static long fnv1a64(byte[] data) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : data) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mur3_64(byte[] data, long seed) {
        long h = seed;
        int len = data.length;
        int i = 0;
        while (i + 8 <= len) {
            long k = ((long) (data[i] & 0xff))
                   | ((long) (data[i + 1] & 0xff) << 8)
                   | ((long) (data[i + 2] & 0xff) << 16)
                   | ((long) (data[i + 3] & 0xff) << 24)
                   | ((long) (data[i + 4] & 0xff) << 32)
                   | ((long) (data[i + 5] & 0xff) << 40)
                   | ((long) (data[i + 6] & 0xff) << 48)
                   | ((long) (data[i + 7] & 0xff) << 56);
            k *= 0x87c37b91114253d5L;
            k = Long.rotateLeft(k, 31);
            k *= 0x4cf5ad432745937fL;
            h ^= k;
            h = Long.rotateLeft(h, 27);
            h = h * 5 + 0x52dce729;
            i += 8;
        }
        if (i < len) {
            long left = 0;
            int j = 0;
            while (i < len) {
                left |= ((long) (data[i] & 0xff)) << (j * 8);
                i++;
                j++;
            }
            left *= 0x87c37b91114253d5L;
            left = Long.rotateLeft(left, 31);
            left *= 0x4cf5ad432745937fL;
            h ^= left;
        }
        h ^= len;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}

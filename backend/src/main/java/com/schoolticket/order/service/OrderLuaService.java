package com.schoolticket.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Redis Lua 脚本执行服务：抢购 + 回滚
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLuaService {

    private final StringRedisTemplate redis;

    private DefaultRedisScript<List> purchaseScript;
    private DefaultRedisScript<List> rollbackScript;

    private static final String STOCK_KEY = "ticket:stock:%d";
    private static final String SOLDOUT_KEY = "ticket:soldout:%d";
    private static final String PURCHASE_KEY = "ticket:purchase:%d";
    private static final String STREAM_KEY = "stream:orders";

    @PostConstruct
    public void init() throws Exception {
        purchaseScript = loadScript("scripts/purchase.lua");
        rollbackScript = loadScript("scripts/rollback.lua");
    }

    private DefaultRedisScript<List> loadScript(String path) throws Exception {
        String src = StreamUtils.copyToString(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(src);
        script.setResultType(List.class);
        return script;
    }

    /**
     * 执行购买 Lua 脚本
     * @param allTicketIds 同一活动下所有票档 ID（用于跨票档检查）
     * @return [code, msg]: 0=成功, -1=售罄, -2=库存不足, -3=限购超限, -4=跨票档
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Object> executePurchase(Long ticketId, Long userId, Long eventId,
                                        String orderId, int quantity, int totalStock,
                                        String totalPrice, long expireTimeMs,
                                        List<Long> allTicketIds) {
        List<String> keys = List.of(
                String.format(STOCK_KEY, ticketId),
                String.format(SOLDOUT_KEY, ticketId),
                String.format(PURCHASE_KEY, ticketId),
                STREAM_KEY
        );
        // Args: [0..7] = 核心参数, [8..] = 其他票档 ID（跨票档检查用）
        String[] baseArgs = {
                orderId,
                String.valueOf(userId),
                String.valueOf(ticketId),
                String.valueOf(quantity),
                "5",
                String.valueOf(totalStock),
                totalPrice,
                String.valueOf(expireTimeMs)
        };
        String[] allArgs = new String[baseArgs.length + allTicketIds.size()];
        System.arraycopy(baseArgs, 0, allArgs, 0, baseArgs.length);
        for (int i = 0; i < allTicketIds.size(); i++) {
            allArgs[baseArgs.length + i] = String.valueOf(allTicketIds.get(i));
        }
        return redis.execute(purchaseScript,
                (RedisSerializer) RedisSerializer.string(),
                (RedisSerializer) RedisSerializer.string(),
                keys,
                (Object[]) allArgs);
    }

    /**
     * 执行回滚 Lua 脚本（取消/退款/超时）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void executeRollback(Long ticketId, Long userId, int quantity) {
        List<String> keys = List.of(
                String.format(STOCK_KEY, ticketId),
                String.format(SOLDOUT_KEY, ticketId),
                String.format(PURCHASE_KEY, ticketId)
        );
        redis.execute(rollbackScript,
                (RedisSerializer) RedisSerializer.string(),
                (RedisSerializer) RedisSerializer.string(),
                keys,
                String.valueOf(userId),
                String.valueOf(quantity));
    }

    /**
     * 预热：设置票档库存（绕过 JSON 序列化器，写裸字符串以便 Lua 读取）
     */
    public void setStock(Long ticketId, int remaining, long expireAtSec) {
        byte[] rawKey = RedisSerializer.string().serialize(String.format(STOCK_KEY, ticketId));
        byte[] rawVal = RedisSerializer.string().serialize(String.valueOf(remaining));
        redis.execute((RedisCallback<Object>) connection -> {
            connection.set(rawKey, rawVal);
            connection.expireAt(rawKey, expireAtSec);
            return null;
        });
    }

    // ===================== 幂等键 =====================

    private static final String IDEMPOTENT_PREFIX = "idempotent:order:";
    private static final int IDEMPOTENT_TTL = 300; // 5 分钟

    /** SET NX 原子抢占幂等键，成功返回 true */
    public boolean tryClaimIdempotentKey(String idempotencyKey) {
        return Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(IDEMPOTENT_PREFIX + idempotencyKey, "PENDING",
                        IDEMPOTENT_TTL, java.util.concurrent.TimeUnit.SECONDS));
    }

    /** 获取幂等键当前值（null=不存在, PENDING=处理中, 其他=订单号） */
    public String getIdempotentResult(String idempotencyKey) {
        return redis.opsForValue().get(IDEMPOTENT_PREFIX + idempotencyKey);
    }

    /** 订单创建成功，将幂等键更新为订单号 */
    public void completeIdempotentKey(String idempotencyKey, String orderNo) {
        redis.opsForValue().set(IDEMPOTENT_PREFIX + idempotencyKey, orderNo, IDEMPOTENT_TTL, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** 业务失败，释放幂等键 */
    public void releaseIdempotentKey(String idempotencyKey) {
        redis.delete(IDEMPOTENT_PREFIX + idempotencyKey);
    }

    /**
     * 获取 Redis 中票档库存（绕过 JSON 反序列化器，读裸字符串）
     */
    public Integer getStock(Long ticketId) {
        byte[] rawKey = RedisSerializer.string().serialize(String.format(STOCK_KEY, ticketId));
        byte[] rawVal = redis.opsForValue().getOperations().execute((RedisCallback<byte[]>) connection ->
                connection.get(rawKey));
        if (rawVal == null) return null;
        return Integer.parseInt(RedisSerializer.string().deserialize(rawVal));
    }
}

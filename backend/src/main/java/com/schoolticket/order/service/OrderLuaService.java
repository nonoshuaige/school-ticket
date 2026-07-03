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
    private static final String PURCHASE_KEY = "event:purchase:%d";
    private static final String STREAM_KEY = "stream:orders";
    private static final String ORDER_CACHE_KEY = "order:%s";

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
     * 执行购买 Lua 脚本（活动级限购：同一 event 下所有票档合计最多 5 张）
     * @return [code, msg]: 0=成功, -1=售罄, -2=库存不足, -3=活动限购超限
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final String DEDUP_KEY = "dedup:order:%d:%d";

    public List<Object> executePurchase(Long ticketId, Long userId, Long eventId,
                                        String orderId, int quantity, int totalStock,
                                        String totalPrice, long expireTimeMs) {
        List<String> keys = List.of(
                String.format(STOCK_KEY, ticketId),
                String.format(SOLDOUT_KEY, ticketId),
                String.format(PURCHASE_KEY, eventId),
                STREAM_KEY,
                String.format(DEDUP_KEY, userId, ticketId)
        );
        String[] args = {
                orderId,
                String.valueOf(userId),
                String.valueOf(ticketId),
                String.valueOf(quantity),
                "5",
                String.valueOf(totalStock),
                totalPrice,
                String.valueOf(expireTimeMs)
        };
        return redis.execute(purchaseScript,
                (RedisSerializer) RedisSerializer.string(),
                (RedisSerializer) RedisSerializer.string(),
                keys,
                (Object[]) args);
    }

    /**
     * 执行回滚 Lua 脚本（取消/退款/超时）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void executeRollback(Long ticketId, Long eventId, Long userId, int quantity) {
        List<String> keys = List.of(
                String.format(STOCK_KEY, ticketId),
                String.format(SOLDOUT_KEY, ticketId),
                String.format(PURCHASE_KEY, eventId)
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

    // ===================== 订单缓存 =====================

    private static final String USER_ORDERS_KEY = "user:orders:%d";

    /** 从 Redis 读取订单 JSON */
    public String getOrderCache(String orderNo) {
        return redis.opsForValue().get(String.format(ORDER_CACHE_KEY, orderNo));
    }

    /** Consumer 落库后 / 状态变更后 更新 Redis 订单缓存 */
    public void updateOrderCache(String orderNo, String json) {
        redis.opsForValue().set(String.format(ORDER_CACHE_KEY, orderNo), json, 1800, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** 下单时 LPUSH 订单号到用户列表，保留最近 10 条 */
    public void pushUserOrderList(Long userId, String orderNo) {
        String key = String.format(USER_ORDERS_KEY, userId);
        redis.opsForList().leftPush(key, orderNo);
        redis.opsForList().trim(key, 0, 9);
        redis.expire(key, 1800, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** 读取用户最近订单号列表 */
    public List<String> getUserOrderList(Long userId) {
        return redis.opsForList().range(String.format(USER_ORDERS_KEY, userId), 0, -1);
    }

    /** 批量读取订单缓存 */
    public List<String> multiGetOrderCache(List<String> orderNos) {
        List<String> keys = orderNos.stream().map(no -> String.format(ORDER_CACHE_KEY, no)).toList();
        return redis.opsForValue().multiGet(keys);
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

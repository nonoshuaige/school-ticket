package com.schoolticket.order.consumer;

import com.schoolticket.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;

/**
 * Stream → RabbitMQ 桥接 + PEL 兜底
 *
 * 主路径: XREADGROUP 新消息 → publish RabbitMQ → XACK (每 200ms)
 * 兜底:   XPENDING 扫描 → XCLAIM 卡住消息 → publish RabbitMQ → XACK (每 5s)
 *
 * 设计: Lua 只能原子写 Stream, 不能调 RabbitMQ。
 *       本桥接线程将消息从 Redis 内存级可靠升级为 RabbitMQ 磁盘级可靠。
 *       RabbitMQ Consumer 是唯⼀落库路径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamToRabbitMQBridge {

    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbitTemplate;

    private static final String STREAM_KEY = "stream:orders";
    private static final String GROUP = "order-consumers";
    private static final String CONSUMER = "bridge-1";

    @PostConstruct
    public void init() {
        try {
            redis.opsForStream().createGroup(STREAM_KEY, GROUP);
        } catch (Exception e) {
            // Group already exists
        }
    }

    /** 主路径: 消费新消息 → RabbitMQ → ACK */
    @Scheduled(fixedDelay = 200)
    public void bridgeNew() {
        try {
            @SuppressWarnings("unchecked")
            List<MapRecord<String, Object, Object>> messages = redis.opsForStream()
                    .read(Consumer.from(GROUP, CONSUMER),
                            StreamReadOptions.empty().count(10).block(Duration.ofSeconds(1)),
                            StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

            if (messages == null || messages.isEmpty()) return;

            for (MapRecord<String, Object, Object> record : messages) {
                try {
                    Map<String, Object> msg = toMessageMap(record.getValue());
                    rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE,
                            RabbitMQConfig.RK_ORDER_CREATE, msg);
                    redis.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                } catch (Exception e) {
                    log.error("Bridge publish failed, will retry via PEL: entryId={}", record.getId(), e);
                    // 不 ACK，PEL 兜底会重新投递
                }
            }
        } catch (Exception e) {
            log.error("bridgeNew error", e);
        }
    }

    /** 兜底: 扫描 PEL 中卡住 > 5s 的消息 → XCLAIM → RabbitMQ → ACK */
    @Scheduled(fixedDelay = 5000)
    public void bridgePending() {
        try {
            PendingMessages pending = redis.opsForStream()
                    .pending(STREAM_KEY, GROUP, Range.unbounded(), 100);
            if (pending == null || pending.isEmpty()) return;

            List<String> stuckIds = new ArrayList<>();
            for (PendingMessage pm : pending) {
                if (pm.getElapsedTimeSinceLastDelivery().toMillis() > 5000) {
                    stuckIds.add(pm.getIdAsString());
                }
            }
            if (stuckIds.isEmpty()) return;

            // 直接通过 ID 字符串读取消息内容 → 投递 RabbitMQ → ACK
            for (String entryId : stuckIds) {
                try {
                    @SuppressWarnings("unchecked")
                    List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                            .range(STREAM_KEY, Range.closed(entryId, entryId));

                    if (records != null && !records.isEmpty()) {
                        Map<String, Object> msg = toMessageMap(records.get(0).getValue());
                        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE,
                                RabbitMQConfig.RK_ORDER_CREATE, msg);
                    }
                    // XACK 确认（即使已 ACK 也不报错）
                    redis.opsForStream().acknowledge(STREAM_KEY, GROUP, entryId);
                    log.info("PEL 兜底投递成功: entryId={}", entryId);
                } catch (Exception e) {
                    log.error("PEL 兜底投递失败: entryId={}", entryId, e);
                }
            }
        } catch (Exception e) {
            log.error("bridgePending error", e);
        }
    }

    private Map<String, Object> toMessageMap(Map<Object, Object> fields) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("orderId",    String.valueOf(fields.get("orderId")));
        msg.put("userId",     Long.valueOf(String.valueOf(fields.get("userId"))));
        msg.put("ticketId",   Long.valueOf(String.valueOf(fields.get("ticketId"))));
        msg.put("quantity",   Integer.parseInt(String.valueOf(fields.get("quantity"))));
        msg.put("totalPrice", String.valueOf(fields.get("totalPrice")));
        msg.put("expireTime", Long.parseLong(String.valueOf(fields.get("expireTime"))));
        return msg;
    }
}

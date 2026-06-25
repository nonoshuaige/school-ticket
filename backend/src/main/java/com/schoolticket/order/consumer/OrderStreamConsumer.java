package com.schoolticket.order.consumer;

import com.schoolticket.order.entity.Order;
import com.schoolticket.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream 消费者：异步将订单落库 MySQL。
 * 每 200ms 拉取一批消息，消费组保证 at-least-once + 幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStreamConsumer {

    private final StringRedisTemplate redis;
    private final OrderMapper orderMapper;

    private static final String STREAM_KEY = "stream:orders";
    private static final String GROUP = "order-consumers";
    private static final String CONSUMER = "consumer-1";

    @PostConstruct
    public void init() {
        try {
            redis.opsForStream().createGroup(STREAM_KEY, GROUP);
        } catch (Exception e) {
            // Group already exists — ignore
        }
    }

    @Scheduled(fixedDelay = 200)
    public void consume() {
        try {
            // 1. 先处理 PEL 中未确认的旧消息
            processPending();

            // 2. 读取新消息
            @SuppressWarnings("unchecked")
            List<MapRecord<String, Object, Object>> messages = redis.opsForStream()
                    .read(org.springframework.data.redis.connection.stream.Consumer.from(GROUP, CONSUMER),
                            org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                                    .count(10).block(Duration.ofSeconds(1)),
                            org.springframework.data.redis.connection.stream.StreamOffset
                                    .create(STREAM_KEY, org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));

            if (messages == null || messages.isEmpty()) return;

            for (MapRecord<String, Object, Object> record : messages) {
                processRecord(record);
            }
        } catch (Exception e) {
            log.error("OrderStreamConsumer error", e);
        }
    }

    private void processPending() {
        try {
            PendingMessages pending = redis.opsForStream()
                    .pending(STREAM_KEY, GROUP, Range.unbounded(), 100);
            if (pending == null || pending.isEmpty()) return;

            for (PendingMessage pm : pending) {
                @SuppressWarnings("unchecked")
                List<MapRecord<String, Object, Object>> claimed = redis.opsForStream()
                        .range(STREAM_KEY, Range.just(pm.getIdAsString()));

                if (claimed != null && !claimed.isEmpty()) {
                    processRecord(claimed.get(0));
                }
            }
        } catch (Exception e) {
            log.error("processPending error", e);
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> fields = record.getValue();
        String orderId = String.valueOf(fields.get("orderId"));
        Long userId = Long.valueOf(String.valueOf(fields.get("userId")));
        Long ticketId = Long.valueOf(String.valueOf(fields.get("ticketId")));
        int quantity = Integer.parseInt(String.valueOf(fields.get("quantity")));
        BigDecimal totalPrice = new BigDecimal(String.valueOf(fields.get("totalPrice")));
        long expireTimeMs = Long.parseLong(String.valueOf(fields.get("expireTime")));

        try {
            Order order = new Order();
            order.setOrderNo(orderId);
            order.setUserId(userId);
            order.setTicketId(ticketId);
            order.setQuantity(quantity);
            order.setTotalPrice(totalPrice);
            order.setStatus(0);
            order.setExpireTime(LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(expireTimeMs),
                    java.time.ZoneId.of("Asia/Shanghai")));
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 幂等：已存在则跳过
        }

        // ACK
        redis.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
    }
}

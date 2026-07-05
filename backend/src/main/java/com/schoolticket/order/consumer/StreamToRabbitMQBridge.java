package com.schoolticket.order.consumer;

import com.schoolticket.config.RabbitMQConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Stream → RabbitMQ 桥接 + PEL 兜底
 *
 * 主路径: Reader 线程 XREAD BLOCK → LinkedBlockingQueue → Worker 线程 publish RabbitMQ 双队列 → XACK
 * 兜底:   XPENDING 扫描 → XCLAIM 卡住消息 → publish RabbitMQ 双队列 → XACK (每 5s)
 *
 * 设计: Lua 只能原子写 Stream, 不能调 RabbitMQ。
 *       本桥接将消息从 Redis 内存级可靠升级为 RabbitMQ 磁盘级可靠。
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
    private static final int WORKER_COUNT = 4;
    private static final int QUEUE_CAPACITY = 1000;

    private final LinkedBlockingQueue<MapRecord<String, Object, Object>> queue =
            new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        try {
            redis.opsForStream().createGroup(STREAM_KEY, GROUP);
        } catch (Exception e) {
            // Group already exists
        }

        // Reader 线程：阻塞读取 Stream，投递到队列
        Thread reader = new Thread(this::readLoop, "stream-reader");
        reader.setDaemon(true);
        reader.start();

        // Worker 线程：从队列取出 → 发布 RabbitMQ 创建/延时双队列 → ACK
        for (int i = 0; i < WORKER_COUNT; i++) {
            Thread worker = new Thread(this::consumeLoop, "stream-worker-" + i);
            worker.setDaemon(true);
            worker.start();
        }

        log.info("Stream桥接已启动: reader=1, workers={}, queueCapacity={}", WORKER_COUNT, QUEUE_CAPACITY);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        // 中断阻塞在队列上的线程
        Thread.currentThread().getThreadGroup().interrupt();
        log.info("Stream桥接已关闭");
    }

    /** Reader: 阻塞读取 Redis Stream，新消息投递到队列 */
    private void readLoop() {
        while (running) {
            try {
                @SuppressWarnings("unchecked")
                List<MapRecord<String, Object, Object>> messages = redis.opsForStream()
                        .read(Consumer.from(GROUP, CONSUMER),
                                StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

                if (messages == null || messages.isEmpty()) continue;

                for (MapRecord<String, Object, Object> record : messages) {
                    queue.put(record); // 背压：队列满时阻塞 reader
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running || isStopping(e)) {
                    break;
                }
                log.error("Stream reader 异常", e);
            }
        }
    }

    /** Worker: 从队列取出 → RabbitMQ 创建/延时双投递 → ACK */
    private void consumeLoop() {
        while (running) {
            try {
                MapRecord<String, Object, Object> record = queue.take();
                try {
                    Map<String, Object> msg = toMessageMap(record.getValue());
                    publishOrderMessages(msg);
                    redis.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                } catch (Exception e) {
                    log.error("Bridge publish failed, will retry via PEL: entryId={}", record.getId(), e);
                    // 不 ACK，PEL 兜底会重新投递
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Stream worker 异常", e);
            }
        }
    }

    /** 兜底: 扫描 PEL 中卡住 > 5s 的消息 → XRANGE → RabbitMQ 创建/延时双投递 → ACK */
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

            for (String entryId : stuckIds) {
                try {
                    @SuppressWarnings("unchecked")
                    List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                            .range(STREAM_KEY, Range.closed(entryId, entryId));

                    if (records != null && !records.isEmpty()) {
                        Map<String, Object> msg = toMessageMap(records.get(0).getValue());
                        publishOrderMessages(msg);
                    }
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

    private void publishOrderMessages(Map<String, Object> msg) throws Exception {
        publishAndWaitConfirm(RabbitMQConfig.RK_ORDER_CREATE, msg);
        publishAndWaitConfirm(RabbitMQConfig.RK_ORDER_DELAY, msg);
    }

    private void publishAndWaitConfirm(String routingKey, Map<String, Object> msg) throws Exception {
        CorrelationData correlationData = new CorrelationData(
                routingKey + ":" + msg.get("orderId") + ":" + UUID.randomUUID());
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, routingKey, msg, correlationData);

        CorrelationData.Confirm confirm = correlationData.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirm.isAck()) {
            throw new IllegalStateException("RabbitMQ publish nacked: routingKey=" + routingKey
                    + ", orderId=" + msg.get("orderId")
                    + ", reason=" + confirm.getReason());
        }
    }

    private boolean isStopping(Exception e) {
        return e instanceof IllegalStateException
                && e.getMessage() != null
                && e.getMessage().contains("STOPPING");
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

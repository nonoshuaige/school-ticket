package com.schoolticket.order.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolticket.order.entity.Order;
import com.schoolticket.order.mapper.OrderMapper;
import com.schoolticket.order.service.OrderLuaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * RabbitMQ 消费者：唯⼀落库路径。
 * 消息来源: StreamToRabbitMQBridge 从 Redis Stream 投递到 RabbitMQ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreateConsumer {

    private final OrderMapper orderMapper;
    private final OrderLuaService orderLuaService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "#{orderCreateQueue.name}")
    public void handleOrderCreate(Map<String, Object> msg) {
        try {
            String orderNo   = (String) msg.get("orderId");
            Long userId      = ((Number) msg.get("userId")).longValue();
            Long ticketId    = ((Number) msg.get("ticketId")).longValue();
            int quantity     = ((Number) msg.get("quantity")).intValue();
            BigDecimal totalPrice = new BigDecimal((String) msg.get("totalPrice"));
            long expireTimeMs = ((Number) msg.get("expireTime")).longValue();

            Order order = new Order();
            order.setOrderNo(orderNo);
            order.setUserId(userId);
            order.setTicketId(ticketId);
            order.setQuantity(quantity);
            order.setTotalPrice(totalPrice);
            order.setStatus(0);
            order.setExpireTime(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(expireTimeMs), ZoneId.of("Asia/Shanghai")));

            orderMapper.insert(order);
            log.info("订单已落库: orderNo={}, userId={}, ticketId={}", orderNo, userId, ticketId);

            // 刷新 Redis：排队中(status=-1) → 可见(status=0，含 DB createTime)
            try {
                orderLuaService.updateOrderCache(orderNo, objectMapper.writeValueAsString(order));
            } catch (Exception ignored) {}
        } catch (DuplicateKeyException e) {
            // 幂等：重复投递（PEL 兜底重试）安全跳过
            log.debug("订单已存在(幂等跳过): orderNo={}", msg.get("orderId"));
        } catch (Exception e) {
            log.error("订单落库失败: orderNo={}", msg.get("orderId"), e);
            throw e; // 抛异常触发 RabbitMQ 重试 / DXL
        }
    }
}

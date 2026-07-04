package com.schoolticket.order.consumer;

import com.schoolticket.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ 延时关单消费者。
 *
 * 下单消息进入 order.delay.queue，15 分钟 TTL 到期后死信转发到 order.close.queue。
 * 消费端只关闭仍处于待支付且已过期的订单，已支付/已取消/重复投递会被幂等跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCloseConsumer {

    private final OrderService orderService;

    @RabbitListener(queues = "#{orderCloseQueue.name}", containerFactory = "orderRabbitListenerContainerFactory")
    public void handleOrderClose(Map<String, Object> msg) {
        String orderNo = String.valueOf(msg.get("orderId"));
        try {
            boolean closed = orderService.closeExpiredOrder(orderNo);
            if (closed) {
                log.info("RabbitMQ延时关单成功: orderNo={}", orderNo);
            }
        } catch (Exception e) {
            log.error("RabbitMQ延时关单失败: orderNo={}", orderNo, e);
            throw e;
        }
    }
}

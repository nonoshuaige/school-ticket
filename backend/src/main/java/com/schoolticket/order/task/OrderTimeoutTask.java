package com.schoolticket.order.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schoolticket.order.entity.Order;
import com.schoolticket.order.mapper.OrderMapper;
import com.schoolticket.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单超时关单兜底任务。
 * 主路径由 RabbitMQ order.delay.queue TTL 到期后触发；这里仅扫描明显滞后的超时订单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutTask {

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedRate = 300000)
    public void cancelExpiredOrders() {
        List<Order> expiredOrders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getStatus, 0)
                        .lt(Order::getExpireTime, LocalDateTime.now().minusMinutes(1)));

        if (expiredOrders.isEmpty()) {
            return;
        }

        log.info("超时关单扫描: 发现 {} 笔超时订单", expiredOrders.size());
        for (Order order : expiredOrders) {
            try {
                transactionTemplate.execute(status -> {
                    orderService.expireOrder(order);
                    return null;
                });
            } catch (Exception e) {
                log.error("超时关单失败: orderNo={}", order.getOrderNo(), e);
            }
        }
    }
}

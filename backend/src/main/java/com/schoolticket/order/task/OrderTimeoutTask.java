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
 * 订单超时关单定时任务
 * 每 30 秒扫描一次，将超时未支付的订单自动取消并回补名额
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutTask {

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedRate = 30000)
    public void cancelExpiredOrders() {
        List<Order> expiredOrders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getStatus, 0)
                        .lt(Order::getExpireTime, LocalDateTime.now()));

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

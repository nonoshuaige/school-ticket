package com.schoolticket.order.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schoolticket.order.entity.OrderEventLog;
import com.schoolticket.order.mapper.OrderEventLogMapper;
import com.schoolticket.order.service.OrderLuaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地消息表定时任务 —— 扫描待处理的回滚事件，驱动 Redis Lua 回滚库存。
 *
 * 流程：
 *   cancel/refund/expire → 写 order_event_log (status=0)
 *       ↓
 *   OrderEventLogTask 扫描 status=0 → 执行 rollback.lua
 *       ↓
 *   成功 → status=1 (已确认)
 *   失败 → retry_count+1，超过 5 次 → status=2 (失败)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventLogTask {

    private final OrderEventLogMapper orderEventLogMapper;
    private final OrderLuaService orderLuaService;

    private static final int MAX_RETRY = 5;

    /**
     * 每 10 秒扫描一次待处理事件
     */
    @Scheduled(fixedRate = 10000)
    public void processPendingEvents() {
        List<OrderEventLog> pending = orderEventLogMapper.selectList(
                new LambdaQueryWrapper<OrderEventLog>()
                        .eq(OrderEventLog::getStatus, 0));

        if (pending.isEmpty()) {
            return;
        }

        log.info("消息表扫描: 发现 {} 条待处理事件", pending.size());
        for (OrderEventLog event : pending) {
            try {
                orderLuaService.executeRollback(
                        event.getTicketId(), event.getUserId(), event.getQuantity());
                // Redis 回滚成功 → ack
                event.setStatus(1);
                orderEventLogMapper.updateById(event);
                log.info("消息表 ack: orderNo={}, eventType={}", event.getOrderNo(), event.getEventType());
            } catch (Exception e) {
                log.error("消息表回滚失败: orderNo={}, retry={}",
                        event.getOrderNo(), event.getRetryCount(), e);
                event.setRetryCount(event.getRetryCount() + 1);
                if (event.getRetryCount() >= MAX_RETRY) {
                    event.setStatus(2);
                    log.error("消息表标记失败: orderNo={}, 已达最大重试次数", event.getOrderNo());
                }
                orderEventLogMapper.updateById(event);
            }
        }
    }
}

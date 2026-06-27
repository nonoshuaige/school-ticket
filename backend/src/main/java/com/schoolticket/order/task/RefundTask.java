package com.schoolticket.order.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schoolticket.order.entity.Refund;
import com.schoolticket.order.mapper.RefundMapper;
import com.schoolticket.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 退款表消费任务 —— 扫描待退款记录，执行实际退款操作。
 *
 * 流程：
 *   refundOrder → INSERT refund (status=0)
 *       ↓
 *   RefundTask 扫描 status=0 → 执行实际退款（DB回滚 + 写 event_log 驱动 Redis 回滚）
 *       ↓
 *   成功 → status=1
 *   失败 → status=2
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundTask {

    private final RefundMapper refundMapper;
    private final OrderService orderService;

    @Scheduled(fixedRate = 10000)
    public void processPendingRefunds() {
        List<Refund> pending = refundMapper.selectList(
                new LambdaQueryWrapper<Refund>().eq(Refund::getStatus, 0));

        if (pending.isEmpty()) {
            return;
        }

        log.info("退款表扫描: 发现 {} 条待退款", pending.size());
        for (Refund refund : pending) {
            // 幂等检查：订单可能已被核销或其他终态
            try {
                orderService.processRefund(refund.getOrderNo());
                refund.setStatus(1);
                refundMapper.updateById(refund);
                log.info("退款成功: refundId={}, orderNo={}", refund.getRefundId(), refund.getOrderNo());
            } catch (Exception e) {
                log.error("退款失败: refundId={}, orderNo={}",
                        refund.getRefundId(), refund.getOrderNo(), e);
                refund.setStatus(2);
                refundMapper.updateById(refund);
            }
        }
    }
}

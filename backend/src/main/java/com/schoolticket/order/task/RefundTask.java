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

        log.info("Refund scan found {} pending records", pending.size());
        for (Refund refund : pending) {
            if (refundMapper.claimPending(refund.getRefundId()) == 0) {
                continue;
            }
            try {
                orderService.processRefund(refund.getOrderNo());
                refund.setStatus(1);
                refundMapper.updateById(refund);
                log.info("Refund processed: refundId={}, orderNo={}",
                        refund.getRefundId(), refund.getOrderNo());
            } catch (Exception e) {
                log.error("Refund failed: refundId={}, orderNo={}",
                        refund.getRefundId(), refund.getOrderNo(), e);
                refund.setStatus(2);
                refundMapper.updateById(refund);
            }
        }
    }
}

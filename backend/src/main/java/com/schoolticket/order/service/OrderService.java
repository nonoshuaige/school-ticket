package com.schoolticket.order.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schoolticket.common.BusinessException;
import com.schoolticket.dto.OrderCreateReq;
import com.schoolticket.dto.OrderVO;
import com.schoolticket.event.entity.Event;
import com.schoolticket.event.entity.TicketCategory;
import com.schoolticket.event.mapper.EventMapper;
import com.schoolticket.event.mapper.TicketCategoryMapper;
import com.schoolticket.order.entity.Order;
import com.schoolticket.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final EventMapper eventMapper;

    // ===================== 核心履约 =====================

    /**
     * 创建订单（名额扣减）
     * 事务内：FOR UPDATE 锁行 → 校验余量 → 扣减 → 生成订单
     */
    @Transactional
    public Order createOrder(Long userId, OrderCreateReq req) {
        // 1. 悲观锁锁定票档行
        TicketCategory ticket = ticketCategoryMapper.selectForUpdate(req.getTicketId());
        if (ticket == null) {
            throw new BusinessException(BusinessException.INVALID_PARAM, "票档不存在");
        }

        // 2. 校验余量
        if (ticket.getRemainingQuantity() < req.getQuantity()) {
            throw new BusinessException(BusinessException.TICKET_SOLD_OUT, "余票不足");
        }

        // 3. 扣减名额
        ticket.setRemainingQuantity(ticket.getRemainingQuantity() - req.getQuantity());
        ticketCategoryMapper.updateById(ticket);

        // 4. 创建订单
        Order order = new Order();
        order.setOrderNo(IdUtil.getSnowflakeNextIdStr());
        order.setUserId(userId);
        order.setTicketId(req.getTicketId());
        order.setQuantity(req.getQuantity());
        order.setTotalPrice(ticket.getPrice().multiply(BigDecimal.valueOf(req.getQuantity())));
        order.setStatus(0);                     // 待支付
        order.setExpireTime(LocalDateTime.now().plusMinutes(15)); // 15分钟支付窗口
        orderMapper.insert(order);

        log.info("订单创建: orderNo={}, userId={}, ticketId={}, qty={}",
                order.getOrderNo(), userId, req.getTicketId(), req.getQuantity());
        return order;
    }

    /**
     * 模拟支付
     */
    @Transactional
    public Order payOrder(String orderNo) {
        Order order = getOrder(orderNo);
        validateStatus(order, 0, "订单不是待支付状态");
        if (LocalDateTime.now().isAfter(order.getExpireTime())) {
            throw new BusinessException(BusinessException.ORDER_EXPIRED, "订单已过期");
        }
        order.setStatus(1);
        order.setPaidTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("订单支付: orderNo={}", orderNo);
        return order;
    }

    /**
     * 主动取消（未支付）
     */
    @Transactional
    public Order cancelOrder(String orderNo) {
        Order order = getOrder(orderNo);
        validateStatus(order, 0, "只有待支付订单可取消");
        replenishTicket(order);
        order.setStatus(2);
        order.setCancelTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("订单取消: orderNo={}", orderNo);
        return order;
    }

    /**
     * 退款（已支付 → 退款）
     */
    @Transactional
    public Order refundOrder(String orderNo) {
        Order order = getOrder(orderNo);
        validateStatus(order, 1, "只有已支付订单可退款");
        replenishTicket(order);
        order.setStatus(3);
        order.setRefundTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("订单退款: orderNo={}", orderNo);
        return order;
    }

    /**
     * 核销
     */
    @Transactional
    public Order useOrder(String orderNo) {
        Order order = getOrder(orderNo);
        validateStatus(order, 1, "只有已支付订单可核销");
        order.setStatus(4);
        order.setUseTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("订单核销: orderNo={}", orderNo);
        return order;
    }

    // ===================== 查询 =====================

    public IPage<OrderVO> listOrders(Long userId, Integer pageNum, Integer pageSize, Integer status) {
        Page<Order> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId);
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }
        wrapper.orderByDesc(Order::getCreateTime);
        return orderMapper.selectPage(page, wrapper).convert(this::convertToVO);
    }

    public OrderVO getOrderDetail(String orderNo) {
        Order order = getOrder(orderNo);
        return convertToVO(order);
    }

    // ===================== 内部方法 =====================

    private Order getOrder(String orderNo) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException("订单不存在");
        }
        return order;
    }

    private void validateStatus(Order order, int expected, String msg) {
        if (order.getStatus() != expected) {
            throw new BusinessException(BusinessException.ORDER_STATUS_ERROR, msg);
        }
    }

    /**
     * 回补票档名额（取消/退票时调用）
     */
    private void replenishTicket(Order order) {
        TicketCategory ticket = ticketCategoryMapper.selectById(order.getTicketId());
        if (ticket != null) {
            ticket.setRemainingQuantity(ticket.getRemainingQuantity() + order.getQuantity());
            ticketCategoryMapper.updateById(ticket);
        }
    }

    /**
     * 将 Order 实体转为含活动/票档信息的 OrderVO
     */
    private OrderVO convertToVO(Order order) {
        TicketCategory ticket = ticketCategoryMapper.selectById(order.getTicketId());
        OrderVO vo = new OrderVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        vo.setQuantity(order.getQuantity());
        vo.setTotalPrice(order.getTotalPrice());
        vo.setStatus(order.getStatus());
        vo.setExpireTime(order.getExpireTime());
        vo.setCreateTime(order.getCreateTime());
        vo.setPaidTime(order.getPaidTime());
        vo.setUseTime(order.getUseTime());

        if (ticket != null) {
            vo.setTicketName(ticket.getName());
            vo.setTicketPrice(ticket.getPrice());
            Event event = eventMapper.selectById(ticket.getEventId());
            if (event != null) {
                vo.setEventId(event.getEventId());
                vo.setEventTitle(event.getTitle());
                vo.setEventVenue(event.getVenue());
                vo.setEventStartTime(event.getEventStartTime());
            }
        }
        return vo;
    }

    // ===================== 定时任务用 =====================

    /**
     * 超时关单 —— 由 OrderTimeoutTask 调用
     * 对单个订单执行关单（含回补）
     */
    @Transactional
    public void expireOrder(Order order) {
        replenishTicket(order);
        order.setStatus(2);
        order.setCancelTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("超时关单: orderNo={}", order.getOrderNo());
    }
}

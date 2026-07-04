package com.schoolticket.order.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schoolticket.common.BusinessException;
import com.schoolticket.dto.EventOrderGroupVO;
import com.schoolticket.dto.OrderCreateReq;
import com.schoolticket.dto.OrderVO;
import com.schoolticket.event.entity.Event;
import com.schoolticket.event.entity.TicketCategory;
import com.schoolticket.event.mapper.EventMapper;
import com.schoolticket.event.mapper.TicketCategoryMapper;
import com.schoolticket.order.cache.RateLimiter;
import com.schoolticket.order.cache.SoldOutCache;
import com.schoolticket.order.entity.Order;
import com.schoolticket.order.entity.OrderEventLog;
import com.schoolticket.order.entity.Refund;
import com.schoolticket.order.mapper.OrderEventLogMapper;
import com.schoolticket.order.mapper.OrderMapper;
import com.schoolticket.order.mapper.RefundMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderEventLogMapper orderEventLogMapper;
    private final RefundMapper refundMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final EventMapper eventMapper;
    private final OrderLuaService orderLuaService;
    private final SoldOutCache soldOutCache;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    private static final int EXPIRE_MINUTES = 15;

    // ===================== 核心履约 =====================

    /**
     * 创建订单（四层预检快速失败 + Lua 原子写入）
     *
     * 预检链: 用户限流 → Caffeine售罄 → Redis售罄 → Redis库存 → Redis限购（概率递减）
     * Lua 内仍做安全校验，并原子写入订单缓存(status=-1排队中) + Stream。
     * Consumer 落库后刷新缓存 status=0，订单变为可见。
     * 全程查询走 Redis，不穿透 DB。
     */
    public Order createOrder(Long userId, OrderCreateReq req) {
        // 0. 用户级请求频率限制（每人每秒最多1次，防脚本/连点）
        if (rateLimiter.isRateLimited(userId)) {
            throw new BusinessException("操作太频繁，请稍后再试");
        }

        // 1. Caffeine 本地售罄缓存短路
        if (soldOutCache.checkSoldOut(req.getTicketId())) {
            throw new BusinessException(BusinessException.TICKET_SOLD_OUT, "该票档已售罄");
        }

        // 2. Redis 售罄标记（Java 侧快速失败，减少无效 Lua 调用）
        if (orderLuaService.isSoldOut(req.getTicketId())) {
            soldOutCache.markSoldOut(req.getTicketId());
            throw new BusinessException(BusinessException.TICKET_SOLD_OUT, "该票档已售罄");
        }

        // 3. 查票档 + 活动信息
        TicketCategory ticket = ticketCategoryMapper.selectById(req.getTicketId());
        if (ticket == null) {
            throw new BusinessException(BusinessException.INVALID_PARAM, "票档不存在");
        }
        Event event = eventMapper.selectById(ticket.getEventId());
        if (event == null) {
            throw new BusinessException(BusinessException.INVALID_PARAM, "活动不存在");
        }

        // 4. Redis 库存预检（快速失败，减少无效 Lua 调用）
        Integer stock = orderLuaService.getStock(req.getTicketId());
        if (stock != null && stock < req.getQuantity()) {
            soldOutCache.markSoldOut(req.getTicketId());
            throw new BusinessException(BusinessException.TICKET_SOLD_OUT, "该票档已售罄");
        }

        // 5. 活动级限购预检（同一活动所有票档合计最多 5 张）
        int purchased = orderLuaService.getPurchaseCount(event.getEventId(), userId);
        if (purchased + req.getQuantity() > 5) {
            throw new BusinessException("该活动累计最多购买5张，已购" + purchased + "张");
        }

        // 6. 预生成订单号，构建排队中订单
        String orderNo = IdUtil.getSnowflakeNextIdStr();
        BigDecimal totalPrice = ticket.getPrice().multiply(BigDecimal.valueOf(req.getQuantity()));
        long expireTimeMs = LocalDateTime.now().plusMinutes(EXPIRE_MINUTES)
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();

        Order stub = buildStubOrder(orderNo, userId, req, totalPrice, ticket);
        stub.setStatus(-1); // 排队中：Lua 已写 Redis，尚未落库
        String orderJson;
        try {
            orderJson = objectMapper.writeValueAsString(stub);
        } catch (Exception e) {
            throw new BusinessException("订单序列化失败");
        }

        // 7. 执行 Lua（原子：安全校验 + 扣库存 + 限购 + 写订单缓存 + Stream）
        List<Object> result = orderLuaService.executePurchase(
                req.getTicketId(), userId, event.getEventId(), orderNo,
                req.getQuantity(), totalPrice.toString(), expireTimeMs, orderJson);

        long code = result.get(0) instanceof Long ? (Long) result.get(0)
                : ((Number) result.get(0)).longValue();

        if (code == -1 || code == -2) {
            soldOutCache.markSoldOut(req.getTicketId());
            throw new BusinessException(BusinessException.TICKET_SOLD_OUT, "该票档已售罄");
        }
        if (code == -3) {
            throw new BusinessException("该活动累计最多购买5张");
        }

        // 8. 用户订单列表（best effort，主流程已完成）
        try {
            orderLuaService.pushUserOrderList(userId, orderNo);
        } catch (Exception ignored) {}

        log.info("订单创建(排队中): orderNo={}, userId={}, ticketId={}, qty={}",
                orderNo, userId, req.getTicketId(), req.getQuantity());
        return stub;
    }

    private Order buildStubOrder(String orderNo, Long userId, OrderCreateReq req,
                                  BigDecimal totalPrice, TicketCategory ticket) {
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setTicketId(req.getTicketId());
        order.setQuantity(req.getQuantity());
        order.setTotalPrice(totalPrice);
        order.setStatus(0);
        order.setExpireTime(LocalDateTime.now().plusMinutes(EXPIRE_MINUTES));
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
        refreshOrderCache(orderNo, order);
        log.info("订单支付: orderNo={}", orderNo);
        return order;
    }

    /**
     * 主动取消（未支付）→ DB 回滚 + 写本地消息表驱动 Redis 回滚
     */
    @Transactional
    public Order cancelOrder(String orderNo) {
        Order order = getOrder(orderNo);
        validateStatus(order, 0, "只有待支付订单可取消");
        replenishTicket(order);
        order.setStatus(2);
        order.setCancelTime(LocalDateTime.now());
        orderMapper.updateById(order);

        // 写本地消息表，由定时任务异步驱动 Redis Lua 回滚，保证逆向链路可靠
        writeEventLog(order, 1);

        refreshOrderCache(orderNo, order);
        log.info("订单取消: orderNo={}", orderNo);
        return order;
    }

    /**
     * 退款（已支付 → 退款）→ 写退款表，由 RefundTask 异步消费
     */
    @Transactional
    public Order refundOrder(String orderNo) {
        Order order = getOrder(orderNo);
        validateStatus(order, 1, "只有已支付订单可退款");

        Refund refund = new Refund();
        refund.setRefundId(orderNo);
        refund.setOrderNo(orderNo);
        refund.setUserId(order.getUserId());
        refund.setTicketId(order.getTicketId());
        refund.setQuantity(order.getQuantity());
        refund.setTotalPrice(order.getTotalPrice());
        refund.setStatus(0);
        refundMapper.insert(refund);

        log.info("退款申请已入表: orderNo={}", orderNo);
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
        refreshOrderCache(orderNo, order);
        log.info("订单核销: orderNo={}", orderNo);
        return order;
    }

    /**
     * 消费退款表执行实际退款（由 RefundTask 调用）
     * DB 回滚 + 写本地消息表驱动 Redis 回滚
     */
    @Transactional
    public void processRefund(String orderNo) {
        Order order = getOrder(orderNo);
        replenishTicket(order);
        order.setStatus(3);
        order.setRefundTime(LocalDateTime.now());
        orderMapper.updateById(order);

        writeEventLog(order, 2);
        refreshOrderCache(orderNo, order);

        log.info("退款执行完成: orderNo={}", orderNo);
    }

    // ===================== 查询 =====================

    public IPage<EventOrderGroupVO> listOrders(Long userId, Integer pageNum, Integer pageSize, Integer status) {
        // Redis 路径：无状态筛选 + 首页（缓存最多 10 条）
        if (status == null && pageNum == 1) {
            List<Order> orders = loadOrdersFromCache(userId);
            if (!orders.isEmpty()) {
                return groupAndPage(orders, pageNum, pageSize);
            }
        }
        // DB 路径：状态筛选 / 翻页 / 缓存 miss
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId);
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }
        wrapper.orderByDesc(Order::getCreateTime);
        List<Order> allOrders = orderMapper.selectList(wrapper);
        return groupAndPage(allOrders, pageNum, pageSize);
    }

    private List<Order> loadOrdersFromCache(Long userId) {
        List<String> orderNos = orderLuaService.getUserOrderList(userId);
        if (orderNos == null || orderNos.isEmpty()) return List.of();
        List<Order> orders = new ArrayList<>();
        for (String no : orderNos) {
            String json = orderLuaService.getOrderCache(no);
            if (json != null) {
                try {
                    orders.add(objectMapper.readValue(json, Order.class));
                } catch (Exception ignored) {}
            }
        }
        return orders;
    }

    private IPage<EventOrderGroupVO> groupAndPage(List<Order> allOrders, int pageNum, int pageSize) {
        Map<Long, EventOrderGroupVO> groupMap = new LinkedHashMap<>();
        for (Order order : allOrders) {
            OrderVO vo = convertToVO(order);
            EventOrderGroupVO group = groupMap.computeIfAbsent(vo.getEventId(), eid -> {
                EventOrderGroupVO g = new EventOrderGroupVO();
                g.setEventId(vo.getEventId());
                g.setEventTitle(vo.getEventTitle());
                g.setEventVenue(vo.getEventVenue());
                g.setEventStartTime(vo.getEventStartTime());
                g.setOrders(new ArrayList<>());
                return g;
            });
            group.getOrders().add(vo);
        }

        List<EventOrderGroupVO> allGroups = new ArrayList<>(groupMap.values());
        int total = allGroups.size();
        int from = (pageNum - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        List<EventOrderGroupVO> pageGroups = from < total ? allGroups.subList(from, to) : new ArrayList<>();

        Page<EventOrderGroupVO> result = new Page<>(pageNum, pageSize, total);
        result.setRecords(pageGroups);
        return result;
    }

    public OrderVO getOrderDetail(String orderNo) {
        // 1. Redis 优先（Lua 原子写入，无窗口期）
        String cached = orderLuaService.getOrderCache(orderNo);
        if (cached != null) {
            try {
                Order order = objectMapper.readValue(cached, Order.class);
                return convertToVO(order);
            } catch (Exception e) {
                log.warn("订单缓存反序列化失败, 回源MySQL: orderNo={}", orderNo, e);
            }
        }
        // 2. Redis miss → MySQL 兜底
        Order order = getOrder(orderNo);
        // 回填缓存
        try {
            orderLuaService.updateOrderCache(orderNo, objectMapper.writeValueAsString(order));
        } catch (Exception ignored) {}
        return convertToVO(order);
    }

    // ===================== 内部方法 =====================

    private void refreshOrderCache(String orderNo, Order order) {
        try {
            orderLuaService.updateOrderCache(orderNo, objectMapper.writeValueAsString(order));
        } catch (Exception ignored) {}
    }

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
     * 回补票档名额（MySQL 层面）
     */
    private void replenishTicket(Order order) {
        TicketCategory ticket = ticketCategoryMapper.selectById(order.getTicketId());
        if (ticket != null) {
            ticket.setRemainingQuantity(ticket.getRemainingQuantity() + order.getQuantity());
            ticketCategoryMapper.updateById(ticket);
        }
    }

    /**
     * 写本地消息表，驱动异步 Redis 回滚
     * 与 MySQL 状态变更在同一事务内，保证原子性
     */
    private void writeEventLog(Order order, int eventType) {
        OrderEventLog eventLog = new OrderEventLog();
        eventLog.setOrderNo(order.getOrderNo());
        eventLog.setEventType(eventType);
        eventLog.setUserId(order.getUserId());
        eventLog.setTicketId(order.getTicketId());
        eventLog.setQuantity(order.getQuantity());
        eventLog.setStatus(0);       // 待处理
        eventLog.setRetryCount(0);
        orderEventLogMapper.insert(eventLog);
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
     * DB 回滚 + 写本地消息表驱动 Redis 回滚
     */
    @Transactional
    public void expireOrder(Order order) {
        replenishTicket(order);
        order.setStatus(2);
        order.setCancelTime(LocalDateTime.now());
        orderMapper.updateById(order);

        writeEventLog(order, 3);
        refreshOrderCache(order.getOrderNo(), order);

        log.info("超时关单: orderNo={}", order.getOrderNo());
    }
}

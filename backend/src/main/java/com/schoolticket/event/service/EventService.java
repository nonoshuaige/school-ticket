package com.schoolticket.event.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schoolticket.event.entity.Event;
import com.schoolticket.event.entity.TicketCategory;
import com.schoolticket.event.mapper.EventMapper;
import com.schoolticket.event.mapper.TicketCategoryMapper;
import com.schoolticket.order.entity.Order;
import com.schoolticket.order.mapper.OrderMapper;
import com.schoolticket.order.service.OrderLuaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventMapper eventMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final OrderMapper orderMapper;
    private final OrderLuaService orderLuaService;

    public IPage<Event> getEventList(Integer status, Integer page, Integer pageSize) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<Event>()
                .orderByAsc(Event::getEventStartTime);
        if (status != null) {
            wrapper.eq(Event::getStatus, status);
        }
        IPage<Event> result = eventMapper.selectPage(new Page<>(page, pageSize), wrapper);
        fillMinPrices(result.getRecords());
        return result;
    }

    private void fillMinPrices(List<Event> events) {
        if (events.isEmpty()) return;
        List<Long> eventIds = events.stream().map(Event::getEventId).collect(Collectors.toList());
        List<TicketCategory> allTickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>().in(TicketCategory::getEventId, eventIds));
        Map<Long, BigDecimal> minPriceMap = allTickets.stream()
                .collect(Collectors.groupingBy(TicketCategory::getEventId,
                        Collectors.collectingAndThen(
                                Collectors.minBy(Comparator.comparing(TicketCategory::getPrice)),
                                opt -> opt.map(TicketCategory::getPrice).orElse(BigDecimal.ZERO))));
        events.forEach(e -> e.setMinPrice(minPriceMap.getOrDefault(e.getEventId(), BigDecimal.ZERO)));
    }

    public Map<String, Object> getEventDetail(Long eventId) {
        Event event = eventMapper.selectById(eventId);
        if (event == null) {
            return null;
        }
        List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId)
                        .orderByAsc(TicketCategory::getPrice));
        Map<String, Object> result = new HashMap<>();
        result.put("event", event);
        result.put("tickets", tickets);
        return result;
    }

    public List<TicketCategory> getTicketsByEvent(Long eventId) {
        return ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId)
                        .orderByAsc(TicketCategory::getPrice));
    }

    public Map<String, Object> getPurchaseStatus(Long eventId, Long userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("purchasedTicketId", null);
        result.put("purchasedQuantity", 0);
        result.put("maxQuantity", 5);

        if (userId == null) {
            return result;
        }

        List<TicketCategory> allTickets = ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, eventId));
        List<Long> ticketIds = allTickets.stream().map(TicketCategory::getTicketId).collect(Collectors.toList());
        if (ticketIds.isEmpty()) {
            return result;
        }

        List<Order> existingOrders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .in(Order::getTicketId, ticketIds)
                        .notIn(Order::getStatus, 2, 3));

        if (!existingOrders.isEmpty()) {
            Long purchasedTicketId = existingOrders.get(0).getTicketId();
            int totalQty = existingOrders.stream().mapToInt(Order::getQuantity).sum();
            result.put("purchasedTicketId", purchasedTicketId);
            result.put("purchasedQuantity", totalQty);
        }
        return result;
    }

    /**
     * 启动时预热：将热卖中活动的票档库存同步到 Redis
     */
    @PostConstruct
    public void preloadStock() {
        try {
            List<Event> hotEvents = eventMapper.selectList(
                    new LambdaQueryWrapper<Event>().eq(Event::getStatus, 1));
            int count = 0;
            for (Event event : hotEvents) {
                long expireAtSec = event.getSaleEndTime()
                        .atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
                List<TicketCategory> tickets = ticketCategoryMapper.selectList(
                        new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getEventId()));
                for (TicketCategory ticket : tickets) {
                    orderLuaService.setStock(ticket.getTicketId(), ticket.getRemainingQuantity(), expireAtSec);
                    count++;
                }
            }
            log.info("库存预热完成: {} 个票档 ({} 个热卖活动)", count, hotEvents.size());
        } catch (Exception e) {
            log.error("库存预热失败", e);
        }
    }
}

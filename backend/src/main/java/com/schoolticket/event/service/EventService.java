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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventMapper eventMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final OrderMapper orderMapper;

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
}

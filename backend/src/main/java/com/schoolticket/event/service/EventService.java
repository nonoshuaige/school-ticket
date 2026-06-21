package com.schoolticket.event.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schoolticket.event.entity.Event;
import com.schoolticket.event.entity.TicketCategory;
import com.schoolticket.event.mapper.EventMapper;
import com.schoolticket.event.mapper.TicketCategoryMapper;
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
}

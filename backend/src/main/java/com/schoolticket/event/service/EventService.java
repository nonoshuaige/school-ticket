package com.schoolticket.event.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schoolticket.event.entity.Event;
import com.schoolticket.event.entity.TicketCategory;
import com.schoolticket.event.mapper.EventMapper;
import com.schoolticket.event.mapper.TicketCategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventMapper eventMapper;
    private final TicketCategoryMapper ticketCategoryMapper;

    public List<Event> getEventList(Integer status) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<Event>()
                .orderByAsc(Event::getEventStartTime);
        if (status != null) {
            wrapper.eq(Event::getStatus, status);
        }
        return eventMapper.selectList(wrapper);
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

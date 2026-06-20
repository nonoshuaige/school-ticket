package com.schoolticket.event.controller;

import com.schoolticket.common.Result;
import com.schoolticket.event.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/event")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping("/list")
    public Result<?> list(@RequestParam(required = false) Integer status) {
        return Result.success(eventService.getEventList(status));
    }

    @GetMapping("/{eventId}")
    public Result<?> detail(@PathVariable Long eventId) {
        Map<String, Object> detail = eventService.getEventDetail(eventId);
        if (detail == null) {
            return Result.error("活动不存在");
        }
        return Result.success(detail);
    }

    @GetMapping("/{eventId}/tickets")
    public Result<?> tickets(@PathVariable Long eventId) {
        return Result.success(eventService.getTicketsByEvent(eventId));
    }
}

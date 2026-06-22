package com.schoolticket.event.controller;

import com.schoolticket.common.CurrentUserHolder;
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
    public Result<?> list(@RequestParam(required = false) Integer status,
                          @RequestParam(defaultValue = "1") Integer page,
                          @RequestParam(defaultValue = "8") Integer pageSize) {
        return Result.success(eventService.getEventList(status, page, pageSize));
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

    @GetMapping("/{eventId}/purchase-status")
    public Result<?> purchaseStatus(@PathVariable Long eventId) {
        Long userId = CurrentUserHolder.getUserId();
        return Result.success(eventService.getPurchaseStatus(eventId, userId));
    }
}

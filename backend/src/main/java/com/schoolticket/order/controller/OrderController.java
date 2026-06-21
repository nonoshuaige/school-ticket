package com.schoolticket.order.controller;

import com.schoolticket.common.CurrentUserHolder;
import com.schoolticket.common.Result;
import com.schoolticket.dto.OrderCreateReq;
import com.schoolticket.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/create")
    public Result<?> create(@Valid @RequestBody OrderCreateReq req) {
        Long userId = CurrentUserHolder.getUserId();
        return Result.success(orderService.createOrder(userId, req));
    }

    @PostMapping("/pay")
    public Result<?> pay(@RequestBody Map<String, String> body) {
        return Result.success(orderService.payOrder(body.get("orderNo")));
    }

    @PostMapping("/cancel")
    public Result<?> cancel(@RequestBody Map<String, String> body) {
        return Result.success(orderService.cancelOrder(body.get("orderNo")));
    }

    @PostMapping("/refund")
    public Result<?> refund(@RequestBody Map<String, String> body) {
        return Result.success(orderService.refundOrder(body.get("orderNo")));
    }

    @PostMapping("/use")
    public Result<?> use(@RequestBody Map<String, String> body) {
        return Result.success(orderService.useOrder(body.get("orderNo")));
    }

    @GetMapping("/list")
    public Result<?> list(@RequestParam(defaultValue = "1") Integer page,
                          @RequestParam(defaultValue = "10") Integer pageSize,
                          @RequestParam(required = false) Integer status) {
        Long userId = CurrentUserHolder.getUserId();
        return Result.success(orderService.listOrders(userId, page, pageSize, status));
    }

    @GetMapping("/{orderNo}")
    public Result<?> detail(@PathVariable String orderNo) {
        return Result.success(orderService.getOrderDetail(orderNo));
    }
}

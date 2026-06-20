package com.schoolticket.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderVO {
    private String orderNo;
    private Long userId;
    private Long eventId;
    private String eventTitle;
    private String eventVenue;
    private LocalDateTime eventStartTime;
    private String ticketName;
    private BigDecimal ticketPrice;
    private Integer quantity;
    private BigDecimal totalPrice;
    private Integer status;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime paidTime;
    private LocalDateTime useTime;
}

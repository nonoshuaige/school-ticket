package com.schoolticket.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("`order`")
public class Order {
    @TableId
    private String orderNo;
    private Long userId;
    private Long ticketId;
    private Integer quantity;
    private BigDecimal totalPrice;
    private Integer status;
    private LocalDateTime expireTime;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    private LocalDateTime paidTime;
    private LocalDateTime cancelTime;
    private LocalDateTime refundTime;
    private LocalDateTime useTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
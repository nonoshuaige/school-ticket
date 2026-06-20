package com.schoolticket.event.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ticket_category")
public class TicketCategory {
    @TableId(type = IdType.AUTO)
    private Long ticketId;
    private Long eventId;
    private String name;
    private BigDecimal price;
    private Integer totalQuantity;
    private Integer remainingQuantity;
    private String description;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

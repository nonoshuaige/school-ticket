package com.schoolticket.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("order_event_log")
public class OrderEventLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    /** 事件类型: 1=取消, 2=退款, 3=超时关单 */
    private Integer eventType;
    private Long userId;
    private Long ticketId;
    private Integer quantity;
    /** 状态: 0=待处理, 1=已确认(Redis回滚成功), 2=失败(超限重试) */
    private Integer status;
    private Integer retryCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

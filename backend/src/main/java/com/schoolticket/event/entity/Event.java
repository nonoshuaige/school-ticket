package com.schoolticket.event.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("event")
public class Event {
    @TableId(type = IdType.AUTO)
    private Long eventId;
    private String title;
    private String description;
    private String organizer;
    private String venue;
    private String posterUrl;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
    private LocalDateTime eventStartTime;
    private LocalDateTime eventEndTime;

    /** 实时计算：0=预热中 1=热卖中 2=已结束 */
    @TableField(exist = false)
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableField(exist = false)
    private BigDecimal minPrice;

    public Integer getStatus() {
        if (saleStartTime == null || saleEndTime == null) return null;
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(saleStartTime)) return 0;
        if (now.isBefore(saleEndTime))   return 1;
        return 2;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}

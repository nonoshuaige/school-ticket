package com.schoolticket.note.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("user_note")
public class Note {
    @TableId(type = IdType.AUTO)
    private Long noteId;
    private Long userId;
    private String content;
    @TableLogic(value = "0", delval = "1")
    private Integer isDeleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

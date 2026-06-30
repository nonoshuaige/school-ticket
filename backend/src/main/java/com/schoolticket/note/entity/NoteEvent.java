package com.schoolticket.note.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("note_event")
public class NoteEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long noteId;
    private Long eventId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

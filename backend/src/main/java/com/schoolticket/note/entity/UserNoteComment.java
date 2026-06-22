package com.schoolticket.note.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("user_note_comment")
public class UserNoteComment {
    @TableId(type = IdType.AUTO)
    private Long commentId;
    private Long noteId;
    private Long userId;
    private String content;
    private Long rootId;
    private Long parentId;
    private Long replyToUid;
    @TableLogic
    private Integer isDeleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

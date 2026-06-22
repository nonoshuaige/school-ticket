package com.schoolticket.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class CommentVO {
    private Long commentId;
    private Long noteId;
    private Long userId;
    private String content;
    private Long rootId;
    private Long parentId;
    private Long replyToUid;
    private LocalDateTime createTime;
    private String nickname;
    private String replyToNickname;
    private List<CommentVO> children;
}

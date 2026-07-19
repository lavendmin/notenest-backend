package com.notenest.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class CommentDTO {
    private UUID commentUuid;

    private String writer;
    private boolean isComposer;
    private String content;

    private LocalDateTime commentCreatedTime;
    private LocalDateTime commentUpdatedTime;

    public CommentDTO(UUID commentUuid, String writer,boolean isComposer, String content,
                      LocalDateTime commentCreatedTime, LocalDateTime commentUpdatedTime) {
        this.commentUuid = commentUuid;
        this.writer = writer;
        this.isComposer = isComposer;
        this.content = content;
        this.commentCreatedTime = commentCreatedTime;
        this.commentUpdatedTime = commentUpdatedTime;
    }
}

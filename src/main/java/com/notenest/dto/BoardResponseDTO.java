package com.notenest.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class BoardResponseDTO {
    private UUID boardUuid;

    private String writer;
    private String title;
    private String content;
    private int hits;

    private LocalDateTime boardCreatedTime;
    private LocalDateTime boardUpdatedTime;

    private List<CommentDTO> comments;

    public BoardResponseDTO(UUID boardUuid, String writer, String title, String content, int hits,
                            LocalDateTime boardCreatedTime, LocalDateTime boardUpdatedTime, List<CommentDTO> comments) {
        this.boardUuid = boardUuid;
        this.writer = writer;
        this.title = title;
        this.content = content;
        this.hits = hits;
        this.boardCreatedTime = boardCreatedTime;
        this.boardUpdatedTime = boardUpdatedTime;
        this.comments = comments;
    }
}

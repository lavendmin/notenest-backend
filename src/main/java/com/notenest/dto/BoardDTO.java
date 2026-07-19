package com.notenest.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class BoardDTO {
    private UUID boardUuid;

    private String writer;
    private String title;
    private String content;

    private LocalDateTime boardCreatedTime;
    private LocalDateTime boardUpdatedTime;

    public BoardDTO(UUID boardUuid, String writer, String title, String content,
                    LocalDateTime boardCreatedTime, LocalDateTime boardUpdatedTime) {
        this.boardUuid = boardUuid;
        this.writer = writer;
        this.title = title;
        this.content = content;
        this.boardCreatedTime = boardCreatedTime;
        this.boardUpdatedTime = boardUpdatedTime;
    }
}

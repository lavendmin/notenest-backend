package com.notenest.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class BoardListDTO {
    private UUID boardUuid;
    private int number;
    private String title;
    private String writer;
    private LocalDateTime boardCreatedTime;
    private int hits;

    public BoardListDTO(UUID boardUuid, int number, String title, String writer,
                        LocalDateTime boardCreatedTime, int hits) {
        this.boardUuid = boardUuid;
        this.number = number;
        this.writer = writer;
        this.title = title;
        this.boardCreatedTime = boardCreatedTime;
        this.hits = hits;
    }
}

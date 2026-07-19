package com.notenest.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class CreateBidDTO {
    private UUID musicUuid;
    private UUID userUuid;
    private double Price; // 입찰 가격
    private LocalDateTime createdAt;

    private String password;
}

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
    private long Price; // 입찰 가격 — 원(KRW) 단위 정수(NB2 금액 계약)
    private LocalDateTime createdAt;

    private String password;
}

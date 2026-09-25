package com.notenest.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class MyBidListDTO {
    private UUID bidUuid;
    private UUID musicUuid;
    private String musicCoverUrl; // 곡 커버 URL(만료 시간 있음)
    private String musicTitle;
    private String composer;
    private long bidPrice;
    private LocalDateTime auctionEndTime;
    private LocalDateTime bidCreatedAt;
    private String status;
}

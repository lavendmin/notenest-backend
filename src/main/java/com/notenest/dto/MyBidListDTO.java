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
    // 커버는 musicCoverUrl, musicImage(base64)는 커버 키가 없는 기존 곡의 전이 기간 fallback.
    private byte[] musicImage;
    private String musicCoverUrl;
    private String musicTitle;
    private String composer;
    private long bidPrice;
    private LocalDateTime auctionEndTime;
    private LocalDateTime bidCreatedAt;
    private String status;
}

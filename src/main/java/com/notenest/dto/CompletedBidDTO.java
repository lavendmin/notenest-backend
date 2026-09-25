package com.notenest.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class CompletedBidDTO {
    private UUID bidUuid;
    private UUID musicUuid;
    private String musicCoverUrl; // 곡 커버 URL(만료 시간 있음)
    private String musicTitle;
    private String composer;
    private long bidPrice;
    private boolean paid;
    private String downloadUrl;
}

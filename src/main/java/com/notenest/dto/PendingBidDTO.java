package com.notenest.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class PendingBidDTO {
    private UUID bidUuid;
    private UUID musicUuid;
    private byte[] musicImage;
    private String musicTitle;
    private String composer;
    private double bidPrice;
    private LocalDateTime auctionEndTime;
    private String paymentUrl;
}

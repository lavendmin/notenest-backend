package com.notenest.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class CompletedBidDTO {
    private UUID bidUuid;
    private UUID musicUuid;
    private byte[] musicImage;
    private String musicTitle;
    private String composer;
    private double bidPrice;
    private boolean paid;
    private String downloadUrl;
    private int downloadCount;
}

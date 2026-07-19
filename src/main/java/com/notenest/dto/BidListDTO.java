package com.notenest.dto;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class BidListDTO {
    private UUID bidUuid;
    private double price;
    private LocalDateTime createdAt;
}

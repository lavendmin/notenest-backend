package com.notenest.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class PaymentReq {
    private String impUid;
    private UUID musicUuid;
    private UUID bidUuid;
}

package com.notenest.dto;

import java.util.UUID;

public class PaymentRes {

    private UUID bidUuid;

    public PaymentRes(UUID bidUuid) {
        this.bidUuid = bidUuid;
    }
}

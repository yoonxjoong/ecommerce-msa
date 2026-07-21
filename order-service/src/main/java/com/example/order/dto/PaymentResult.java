package com.example.order.dto;

import java.util.UUID;

public record PaymentResult(UUID paymentId, Long orderId, String status) {

    public boolean isApproved() {
        return "APPROVED".equals(status);
    }
}

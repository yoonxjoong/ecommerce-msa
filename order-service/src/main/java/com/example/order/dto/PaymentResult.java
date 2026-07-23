package com.example.order.dto;

import java.util.UUID;

public record PaymentResult(UUID paymentId, Long orderId, String status) {

    public static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";

    public static PaymentResult circuitOpen(Long orderId) {
        return new PaymentResult(null, orderId, CIRCUIT_OPEN);
    }

    public boolean isApproved() {
        return "APPROVED".equals(status);
    }

    public boolean isCircuitOpen() {
        return CIRCUIT_OPEN.equals(status);
    }
}

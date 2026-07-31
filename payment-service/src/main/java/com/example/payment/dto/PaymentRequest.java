package com.example.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PaymentRequest(
    @NotNull Long orderId,
    @Positive Long amount,
    boolean simulateFailure
) {
}

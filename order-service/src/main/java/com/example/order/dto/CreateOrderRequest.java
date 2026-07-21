package com.example.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
    @NotNull Long userId,
    @NotNull Long productId,
    @Positive int quantity,
    boolean simulateFailure
) {
}

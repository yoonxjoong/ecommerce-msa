package com.example.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
    @NotNull Long userId,
    @NotNull Long productId,
    @Positive int quantity,
    boolean simulateFailure,
    String queueToken // 상품이 대기열 모드일 때만 필요, 평소엔 null이어도 됨
) {
}

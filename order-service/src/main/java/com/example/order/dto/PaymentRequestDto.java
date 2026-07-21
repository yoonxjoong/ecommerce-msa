package com.example.order.dto;

public record PaymentRequestDto(Long orderId, Long amount, String idempotencyKey, boolean simulateFailure) {
}

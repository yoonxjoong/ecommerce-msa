package com.example.order.dto;

public record PaymentRequestDto(Long orderId, Long amount, boolean simulateFailure) {
}

package com.example.payment.dto;

import com.example.payment.domain.PaymentStatus;
import java.util.UUID;

public record PaymentResponse(UUID paymentId, Long orderId, PaymentStatus status) {
}

package com.example.order.dto;

import com.example.order.domain.Order;

public record OrderResponse(Long id, String status, String failureReason, Long amount) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getStatus().name(), order.getFailureReason(), order.getAmount());
    }
}

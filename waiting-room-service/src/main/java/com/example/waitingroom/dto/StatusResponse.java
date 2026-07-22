package com.example.waitingroom.dto;

public record StatusResponse(boolean admitted, Long position, String token) {
}

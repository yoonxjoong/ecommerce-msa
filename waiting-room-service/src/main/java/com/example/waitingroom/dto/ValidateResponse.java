package com.example.waitingroom.dto;

public record ValidateResponse(boolean valid, String reason) {

    public static ValidateResponse ok() {
        return new ValidateResponse(true, null);
    }

    public static ValidateResponse fail(String reason) {
        return new ValidateResponse(false, reason);
    }
}

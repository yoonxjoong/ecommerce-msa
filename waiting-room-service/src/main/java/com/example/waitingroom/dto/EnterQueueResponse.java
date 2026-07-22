package com.example.waitingroom.dto;

public record EnterQueueResponse(boolean queued, String ticketId, Long position) {

    public static EnterQueueResponse notGated() {
        return new EnterQueueResponse(false, null, null);
    }

    public static EnterQueueResponse queued(String ticketId, long position) {
        return new EnterQueueResponse(true, ticketId, position);
    }
}

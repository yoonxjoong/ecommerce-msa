package com.example.order.service;

public class QueueAccessDeniedException extends RuntimeException {

    public QueueAccessDeniedException(String message) {
        super(message);
    }
}

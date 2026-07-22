package com.example.waitingroom.controller;

import com.example.waitingroom.dto.EnterQueueResponse;
import com.example.waitingroom.dto.StatusResponse;
import com.example.waitingroom.dto.ValidateRequest;
import com.example.waitingroom.dto.ValidateResponse;
import com.example.waitingroom.service.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    /** 대기열 진입 시도. 상품이 대기열 모드가 아니면 즉시 queued=false로 응답 (통과). */
    @PostMapping("/queue/{productId}/enter")
    public EnterQueueResponse enter(@PathVariable Long productId) {
        return queueService.enter(productId);
    }

    /** 순번 폴링. 입장 허가가 나면 token이 채워져서 온다. */
    @GetMapping("/queue/{productId}/status/{ticketId}")
    public StatusResponse status(@PathVariable Long productId, @PathVariable String ticketId) {
        return queueService.status(productId, ticketId);
    }

    /** order-service가 주문 생성 전에 호출 - 대기열 모드가 아니면 항상 통과, 모드면 토큰 검증(1회용). */
    @PostMapping("/queue/{productId}/validate")
    public ValidateResponse validate(@PathVariable Long productId, @RequestBody ValidateRequest request) {
        return queueService.validate(productId, request.token());
    }

    @PostMapping("/admin/queue/{productId}/enable")
    public void enable(@PathVariable Long productId) {
        queueService.enableQueue(productId);
    }

    @PostMapping("/admin/queue/{productId}/disable")
    public void disable(@PathVariable Long productId) {
        queueService.disableQueue(productId);
    }
}

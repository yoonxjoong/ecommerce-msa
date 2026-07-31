package com.example.order.client;

import com.example.order.dto.PaymentRequestDto;
import com.example.order.dto.PaymentResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Slf4j
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Qualifier("paymentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * payment-service가 느려지거나 죽으면, Circuit Breaker가 실패율을 보고 있다가
     * 임계치를 넘으면 OPEN으로 전환된다. OPEN 상태에서는 실제 네트워크 호출 자체를
     * 시도하지 않고 즉시 payFallback으로 빠져서, order-service의 스레드/커넥션이
     * 응답 없는 payment-service를 기다리다 고갈되는 걸 막는다.
     */
    @Retry(name = "paymentService", fallbackMethod = "payFallback")
    @CircuitBreaker(name = "paymentService")
    public PaymentResult pay(Long orderId, Long amount, String idempotencyKey, boolean simulateFailure) {
        return restClient.post()
            .uri("/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new PaymentRequestDto(orderId, amount, idempotencyKey, simulateFailure))
            .retrieve()
            .body(PaymentResult.class);
    }

    @SuppressWarnings("unused")
    private PaymentResult payFallback(Long orderId, Long amount, String idempotencyKey, boolean simulateFailure, Throwable t) {
        log.warn("payment-service 호출 실패(Circuit Breaker fallback), orderId={}, cause={}", orderId, t.toString());
        return PaymentResult.circuitOpen(orderId);
    }
}

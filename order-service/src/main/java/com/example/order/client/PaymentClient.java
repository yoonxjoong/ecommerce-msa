package com.example.order.client;

import com.example.order.dto.PaymentRequestDto;
import com.example.order.dto.PaymentResult;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
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
     *
     * Circuit Breaker는 "실패율이 쌓여야" 반응하는데, payment-service가 완전히 죽지 않고
     * 그냥 느리기만 한 경우(타임아웃 문턱을 간당간당 못 넘는 정도)엔 그 판단이 서기 전까지
     * order-service의 스레드가 계속 이 호출을 기다리며 잠식된다. Bulkhead는 실패 여부와
     * 무관하게 이 호출이 동시에 쓸 수 있는 슬롯 자체를 미리 캡 씌워서, payment-service가
     * 느려져도 inventory-service/waiting-room-service 호출용 스레드까지 잠식하지 못하게 막는다.
     * (SemaphoreBulkhead라 스레드 풀 자체를 분리하는 건 아니고 동시 호출 수만 제한한다 —
     * 진짜 스레드 격리를 하려면 ThreadPoolBulkhead + CompletableFuture 리턴이 필요하다.)
     */
    @Retry(name = "paymentService", fallbackMethod = "payFallback")
    @CircuitBreaker(name = "paymentService")
    @Bulkhead(name = "paymentService")
    public PaymentResult pay(Long orderId, Long amount, String idempotencyKey, boolean simulateFailure) {
        return restClient.post()
            .uri("/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", idempotencyKey)
            .body(new PaymentRequestDto(orderId, amount, simulateFailure))
            .retrieve()
            .body(PaymentResult.class);
    }

    @SuppressWarnings("unused")
    private PaymentResult payFallback(Long orderId, Long amount, String idempotencyKey, boolean simulateFailure, Throwable t) {
        log.warn("payment-service 호출 실패(Circuit Breaker fallback), orderId={}, cause={}", orderId, t.toString());
        return PaymentResult.circuitOpen(orderId);
    }
}

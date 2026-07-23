package com.example.order.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CLOSED -> OPEN -> HALF_OPEN -> CLOSED 전환을 로그로 남겨서, 실제로 언제 회로가
 * 열리고 닫히는지 눈으로 확인할 수 있게 한다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CircuitBreakerEventConfig {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @PostConstruct
    public void registerListeners() {
        circuitBreakerRegistry.circuitBreaker("paymentService")
            .getEventPublisher()
            .onStateTransition(event -> log.warn(
                "[CircuitBreaker] {} : {} -> {}",
                event.getCircuitBreakerName(),
                event.getStateTransition().getFromState(),
                event.getStateTransition().getToState()));
    }
}

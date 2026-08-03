package com.example.payment.service;

import com.example.payment.domain.OutboxEvent;
import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentStatus;
import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.repository.OutboxEventRepository;
import com.example.payment.repository.PaymentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * 결제 승인(mock PG)과 Outbox 이벤트 기록을 하나의 로컬 트랜잭션으로 묶는다.
     * 같은 idempotencyKey로 재시도가 들어와도 중복 승인되지 않도록 먼저 조회한다.
     */
    @Transactional
    public PaymentResponse pay(PaymentRequest request, String idempotencyKey) {
        log.info("결제 요청 수신: orderId={}, amount={}, idempotencyKey={}",
                request.orderId(), request.amount(), idempotencyKey);

        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("멱등 재전달 감지, 기존 결제 결과 그대로 반환: paymentId={}, status={}",
                    existing.get().getId(), existing.get().getStatus());
            return toResponse(existing.get());
        }

        PaymentStatus status = mockPgApprove(request) ? PaymentStatus.APPROVED : PaymentStatus.FAILED;

        Payment payment = paymentRepository.save(Payment.builder()
            .orderId(request.orderId())
            .idempotencyKey(idempotencyKey)
            .amount(request.amount())
            .status(status)
            .build());

        log.info("결제 처리 완료: paymentId={}, orderId={}, status={}",
                payment.getId(), payment.getOrderId(), status);

        String eventType = status == PaymentStatus.APPROVED ? "PaymentCompleted" : "PaymentFailed";
        outboxEventRepository.save(OutboxEvent.builder()
            .aggregateType("PAYMENT")
            .aggregateId(payment.getId().toString())
            .eventType(eventType)
            .payload(toPayload(payment))
            .build());

        return toResponse(payment);
    }

    private boolean mockPgApprove(PaymentRequest request) {
        // 실제 PG 연동이 아니라, 테스트를 위해 simulateFailure 플래그로 결제 실패를 재현하는 mock 로직
        return !request.simulateFailure();
    }

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getStatus());
    }

    private String toPayload(Payment payment) {
        try {
            Map<String, Object> body = Map.of(
                "paymentId", payment.getId().toString(),
                "orderId", payment.getOrderId(),
                "status", payment.getStatus().name()
            );
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 페이로드 직렬화 실패", e);
        }
    }
}

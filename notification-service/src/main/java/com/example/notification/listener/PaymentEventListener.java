package com.example.notification.listener;

import com.example.notification.domain.ProcessedEvent;
import com.example.notification.repository.ProcessedEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox -> Kafka로 발행된 payment-events를 구독해서 "알림"을 보내는(흉내만 내는) 컨슈머.
 * 실제 이메일/푸시 발송 대신 로그로만 남긴다 - 목적은 Outbox 파이프라인 끝에
 * 실제로 반응하는 구독자가 붙는다는 것을 보여주는 것.
 *
 * Inbox 패턴: Kafka는 at-least-once라 같은 이벤트가 재전달될 수 있어서(리밸런싱,
 * 커밋 전 재시작 등), paymentId를 이미 처리했는지 먼저 확인하고, 처리 후 기록한다.
 * processed_event.event_id가 PK라서 동시에 중복 처리가 들어와도 DB 유니크 제약이
 * 마지막 방어선 역할을 한다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentEventListener {

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;

    @KafkaListener(topics = "${payment.topic}", groupId = "notification-service")
    @Transactional
    public void handle(String payload) {
        try {
            Map<String, Object> event = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
            String paymentId = String.valueOf(event.get("paymentId"));

            if (processedEventRepository.existsById(paymentId)) {
                log.info("이미 처리한 이벤트라 건너뜁니다: paymentId={}", paymentId);
                return;
            }

            notify(event);

            processedEventRepository.save(new ProcessedEvent(paymentId));
        } catch (DataIntegrityViolationException e) {
            log.info("동시에 중복 처리 시도가 있었지만 DB 유니크 제약으로 막혔습니다: {}", payload);
        } catch (Exception e) {
            log.error("이벤트 파싱 실패: {}", payload, e);
        }
    }

    private void notify(Map<String, Object> event) {
        Object orderId = event.get("orderId");
        String status = String.valueOf(event.get("status"));

        if ("APPROVED".equals(status)) {
            log.info("[알림] 주문 {} 결제 완료 - 배송 준비 알림을 발송합니다.", orderId);
        } else {
            log.info("[알림] 주문 {} 결제 실패 - 주문 취소 알림을 발송합니다.", orderId);
        }
    }
}

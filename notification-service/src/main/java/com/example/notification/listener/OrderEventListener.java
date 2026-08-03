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
 * order-service의 Outbox -> Kafka로 발행된 order-events를 구독한다.
 *
 * 지금은 PendingOrderTimeoutSweeper가 발행하는 OrderCancelled 이벤트가 유일한 소스다 -
 * payment-service가 요청 자체를 못 받아서 payment-events가 원천적으로 안 생기는 주문은,
 * 이 이벤트가 없으면 고객이 주문이 취소됐다는 사실을 전혀 알 방법이 없다.
 *
 * PaymentEventListener와 동일한 Inbox 패턴(ProcessedEvent, event_id는 OutboxEvent의
 * UUID를 그대로 씀)으로 멱등 처리했다 - payload에 orderId를 이벤트 식별자로 쓰면
 * 같은 주문에 대해 여러 이벤트가 생길 미래 확장에서 충돌할 수 있어서, OutboxEvent 자체의
 * 고유 id를 쓰지 않고 대신 "order-cancelled-{orderId}" 형태로 이벤트 종류까지 포함시켰다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OrderEventListener {

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;

    @KafkaListener(topics = "${order.topic}", groupId = "notification-service")
    @Transactional
    public void handle(String payload) {
        try {
            Map<String, Object> event = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
            Object orderId = event.get("orderId");
            String eventId = "order-cancelled-" + orderId;

            if (processedEventRepository.existsById(eventId)) {
                log.info("이미 처리한 이벤트라 건너뜁니다: eventId={}", eventId);
                return;
            }

            Object reason = event.get("reason");
            log.info("[알림] 주문 {} 결제 시간 초과로 취소되었습니다 (사유: {}) - 주문 취소 알림을 발송합니다.", orderId, reason);

            processedEventRepository.save(new ProcessedEvent(eventId));
        } catch (DataIntegrityViolationException e) {
            log.info("동시에 중복 처리 시도가 있었지만 DB 유니크 제약으로 막혔습니다: {}", payload);
        } catch (Exception e) {
            log.error("이벤트 파싱 실패: {}", payload, e);
        }
    }
}

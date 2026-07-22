package com.example.notification.listener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Outbox -> Kafka로 발행된 payment-events를 구독해서 "알림"을 보내는(흉내만 내는) 컨슈머.
 * 실제 이메일/푸시 발송 대신 로그로만 남긴다 - 목적은 Outbox 파이프라인 끝에
 * 실제로 반응하는 구독자가 붙는다는 것을 보여주는 것.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentEventListener {

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${payment.topic}", groupId = "notification-service")
    public void handle(String payload) {
        try {
            Map<String, Object> event = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
            Object orderId = event.get("orderId");
            String status = String.valueOf(event.get("status"));

            if ("APPROVED".equals(status)) {
                log.info("[알림] 주문 {} 결제 완료 - 배송 준비 알림을 발송합니다.", orderId);
            } else {
                log.info("[알림] 주문 {} 결제 실패 - 주문 취소 알림을 발송합니다.", orderId);
            }
        } catch (Exception e) {
            log.error("이벤트 파싱 실패: {}", payload, e);
        }
    }
}

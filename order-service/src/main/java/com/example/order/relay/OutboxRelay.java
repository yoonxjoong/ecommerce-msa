package com.example.order.relay;

import com.example.order.domain.OutboxEvent;
import com.example.order.repository.OutboxEventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Message Relay(폴링 퍼블리셔): order-service의 Outbox 테이블에서 아직 발행되지
 * 않은 이벤트를 주기적으로 읽어 Kafka로 발행한다. payment-service의 OutboxRelay와
 * 동일한 구조. 지금은 PendingOrderTimeoutSweeper가 남기는 OrderCancelled 이벤트가
 * 유일한 소스다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${order.topic}")
    private String topic;

    @Scheduled(fixedDelayString = "${order.outbox-relay.fixed-delay-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : events) {
            kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload());
            event.markPublished();
            log.info("Published outbox event {} ({})", event.getId(), event.getEventType());
        }
    }
}

package com.example.payment.relay;

import com.example.payment.domain.OutboxEvent;
import com.example.payment.repository.OutboxEventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Message Relay(폴링 퍼블리셔): Outbox 테이블에서 아직 발행되지 않은 이벤트를
 * 주기적으로 읽어 Kafka로 발행한다. 결제 승인 트랜잭션과는 별개로 동작하기 때문에,
 * Kafka가 잠시 장애여도 결제 승인 자체는 영향을 받지 않는다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${payment.topic}")
    private String topic;

    @Scheduled(fixedDelayString = "${payment.outbox-relay.fixed-delay-ms:1000}")
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

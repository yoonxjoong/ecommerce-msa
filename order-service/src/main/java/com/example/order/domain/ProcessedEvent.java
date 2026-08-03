package com.example.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Inbox 패턴: "이 이벤트(paymentId)를 이미 처리했는지"를 기록해서 중복 처리를 막는다.
 * Kafka의 at-least-once 특성상 같은 이벤트가 재전달될 수 있는데(리밸런싱, 재시작 등),
 * event_id를 PK로 둬서 두 번째 insert는 DB 유니크 제약으로도 막히게 한다.
 * notification-service의 동일한 패턴을 그대로 재사용했다.
 */
@Entity
@Table(name = "processed_event")
@Getter
@NoArgsConstructor
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public ProcessedEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = LocalDateTime.now();
    }
}

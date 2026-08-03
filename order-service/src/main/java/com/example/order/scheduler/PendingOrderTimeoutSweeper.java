package com.example.order.scheduler;

import com.example.order.client.InventoryClient;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.OutboxEvent;
import com.example.order.repository.OrderRepository;
import com.example.order.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PaymentEventListener는 payment-service가 요청을 실제로 처리하고 Outbox에 이벤트를
 * 남겼을 때만 동작한다. 만약 Circuit Open이 너무 오래 지속되거나 Bulkhead가 계속
 * 꽉 차있어서 payment-service가 요청 자체를 한 번도 못 받았다면, Payment 레코드도
 * Outbox 이벤트도 영원히 생기지 않아서 주문이 PENDING에 무한정 머무르게 된다.
 * 이 스위퍼가 그 마지막 안전망 - 일정 시간 이상 PENDING인 주문을 강제로 취소/복구한다.
 *
 * 취소와 함께 order-service 자신의 Outbox에도 OrderCancelled 이벤트를 남긴다 - 안 남기면
 * 고객이 "주문이 조용히 취소됐다는 것"을 알림으로 전혀 못 받는다 (이 주문은 payment-service가
 * 요청 자체를 못 받아서 payment-events가 원천적으로 안 생기기 때문에, notification-service가
 * 반응할 이벤트가 이것 말고는 없다).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PendingOrderTimeoutSweeper {

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final InventoryClient inventoryClient;
    private final ObjectMapper objectMapper;

    @Value("${order.pending-timeout-seconds:30}")
    private long pendingTimeoutSeconds;

    @Scheduled(fixedDelayString = "${order.pending-sweep-interval-ms:10000}")
    @Transactional
    public void sweepStuckPendingOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(pendingTimeoutSeconds);
        List<Order> stuckOrders = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, cutoff);

        for (Order order : stuckOrders) {
            inventoryClient.restore(order.getProductId(), order.getQuantity());
            order.cancel("PAYMENT_TIMEOUT");
            orderRepository.save(order);

            outboxEventRepository.save(OutboxEvent.builder()
                    .aggregateType("ORDER")
                    .aggregateId(order.getId().toString())
                    .eventType("OrderCancelled")
                    .payload(toPayload(order))
                    .build());

            log.warn("주문 {} PENDING 타임아웃({}초 경과) - 재고 복구 및 취소 처리, OrderCancelled 이벤트 기록 "
                    + "(payment-events 이벤트가 끝내 도착하지 않음 - payment-service가 요청 자체를 못 받은 것으로 추정)",
                    order.getId(), pendingTimeoutSeconds);
        }
    }

    private String toPayload(Order order) {
        try {
            Map<String, Object> body = Map.of(
                    "orderId", order.getId(),
                    "reason", order.getFailureReason()
            );
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 페이로드 직렬화 실패", e);
        }
    }
}

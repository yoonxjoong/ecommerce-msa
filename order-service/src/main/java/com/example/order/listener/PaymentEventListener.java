package com.example.order.listener;

import com.example.order.client.InventoryClient;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.domain.ProcessedEvent;
import com.example.order.repository.OrderRepository;
import com.example.order.repository.ProcessedEventRepository;
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
 * payment-service의 Outbox -> Kafka로 발행된 payment-events를 구독한다.
 *
 * 평소엔(승인/명시적 거절 응답을 받은 경우) OrderService가 동기 호출 응답으로 이미
 * 확정/취소를 끝내기 때문에, 이 리스너가 할 일이 없다 - 주문이 PENDING이 아니면 그냥 건너뛴다.
 * 이 리스너가 실제로 의미 있는 건, PaymentClient.pay() 호출이 Circuit Open 등으로
 * 응답 자체를 못 받아서 주문이 PENDING으로 남아있던 경우뿐이다: 그때 이 이벤트가
 * "실제로는 어떻게 됐는지"에 대한 유일하게 신뢰할 수 있는 소스가 된다.
 *
 * Inbox 패턴(멱등 처리)은 notification-service의 PaymentEventListener와 동일하게 구현했다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentEventListener {

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;

    @KafkaListener(topics = "${payment.topic}", groupId = "order-service")
    @Transactional
    public void handle(String payload) {
        try {
            Map<String, Object> event = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
            String paymentId = String.valueOf(event.get("paymentId"));

            if (processedEventRepository.existsById(paymentId)) {
                log.info("이미 처리한 이벤트라 건너뜁니다: paymentId={}", paymentId);
                return;
            }

            Long orderId = Long.valueOf(String.valueOf(event.get("orderId")));
            String status = String.valueOf(event.get("status"));
            resolvePendingOrder(orderId, status);

            processedEventRepository.save(new ProcessedEvent(paymentId));
        } catch (DataIntegrityViolationException e) {
            log.info("동시에 중복 처리 시도가 있었지만 DB 유니크 제약으로 막혔습니다: {}", payload);
        } catch (Exception e) {
            log.error("이벤트 파싱/처리 실패: {}", payload, e);
        }
    }

    private void resolvePendingOrder(Long orderId, String status) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("이벤트에 해당하는 주문을 찾을 수 없음: orderId={}", orderId);
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            log.info("주문 {}은(는) 이미 {} 상태라 이벤트로는 처리하지 않음(동기 응답으로 이미 결정됨)",
                    orderId, order.getStatus());
            return;
        }

        if ("APPROVED".equals(status)) {
            order.confirm();
            orderRepository.save(order);
            log.info("주문 {} 이벤트로 확정 - 동기 호출 응답을 못 받았지만 실제로는 결제 성공이었음", orderId);
        } else {
            inventoryClient.restore(order.getProductId(), order.getQuantity());
            order.cancel("PAYMENT_FAILED_VIA_EVENT");
            orderRepository.save(order);
            log.info("주문 {} 이벤트로 취소 및 재고 복구 - 동기 호출 응답을 못 받았지만 실제로는 결제 실패였음", orderId);
        }
    }
}

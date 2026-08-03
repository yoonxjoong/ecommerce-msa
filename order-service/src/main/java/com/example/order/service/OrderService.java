package com.example.order.service;

import com.example.order.client.InventoryClient;
import com.example.order.client.PaymentClient;
import com.example.order.client.WaitingRoomClient;
import com.example.order.domain.Order;
import com.example.order.dto.CreateOrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.dto.PaymentResult;
import com.example.order.dto.ProductDto;
import com.example.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 주문 서비스가 Saga의 오케스트레이터 역할을 한다.
 * 재고 확인/차감, 결제 승인은 각 서비스의 독립된 로컬 트랜잭션이라
 * 여기서는 의도적으로 하나의 @Transactional로 묶지 않고, 각 단계 실패 시
 * 보상 트랜잭션(재고 복구, 주문 취소)을 직접 호출한다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final WaitingRoomClient waitingRoomClient;

    public OrderResponse createOrder(CreateOrderRequest request) {
        log.info("주문 생성 요청: userId={}, productId={}, quantity={}, simulateFailure={}",
                request.userId(), request.productId(), request.quantity(), request.simulateFailure());

        // 대기열 모드가 아닌 상품은 waiting-room-service가 항상 valid=true로 응답하므로
        // 평소 주문 흐름에는 사실상 아무 영향이 없다.
        if (!waitingRoomClient.validate(request.productId(), request.queueToken())) {
            log.info("대기열 토큰 검증 실패: productId={}", request.productId());
            throw new QueueAccessDeniedException("대기열을 통과하지 못했습니다. 입장 토큰을 확인해주세요.");
        }

        ProductDto product = inventoryClient.getProduct(request.productId());
        long amount = product.price() * request.quantity();

        Order order = orderRepository.save(Order.builder()
            .userId(request.userId())
            .productId(request.productId())
            .quantity(request.quantity())
            .amount(amount)
            .build());
        log.info("주문 {} 생성됨(PENDING), amount={}. 재고 확인 단계로 진행", order.getId(), amount);

        boolean reserved = inventoryClient.reserve(request.productId(), request.quantity(), order.getId());
        if (!reserved) {
            log.info("Order {} cancelled: out of stock", order.getId());
            order.cancel("OUT_OF_STOCK");
            orderRepository.save(order);
            return OrderResponse.from(order);
        }
        log.info("주문 {} 재고 확보 완료. 결제 단계로 진행", order.getId());

        String idempotencyKey = "order-" + order.getId();
        PaymentResult paymentResult = paymentClient.pay(order.getId(), amount, idempotencyKey, request.simulateFailure());

        if (paymentResult.isApproved()) {
            // 응답을 확실히 받았다 - 그 자리에서 바로 확정해도 안전하다.
            order.confirm();
            log.info("Order {} confirmed", order.getId());
        } else if (paymentResult.isCircuitOpen()) {
            // 응답 자체를 못 받았다 - payment-service가 실제로 처리했는지 못 했는지 알 방법이
            // 없으므로, 여기서 바로 실패로 간주해서 재고를 복구하면 "사실은 결제가 성공했었는데
            // 재고만 복구해버리는" 정합성 사고가 날 수 있다. 그래서 취소하지 않고 PENDING으로
            // 남겨두고, PaymentEventListener가 payment-events(Outbox 경유, 신뢰 가능한 소스)를
            // 보고 실제 결과에 따라 확정/취소를 결정하게 한다.
            log.info("Order {} 응답 없음(Circuit Open) - PENDING 유지, payment-events 이벤트로 추후 확정/취소",
                    order.getId());
        } else {
            // PG가 명시적으로 거절한 경우 - 이것도 응답을 확실히 받은 것이므로 바로 처리해도 안전하다.
            inventoryClient.restore(request.productId(), request.quantity());
            order.cancel("PAYMENT_FAILED");
            log.info("Order {} cancelled: PAYMENT_FAILED, stock restored", order.getId());
        }

        orderRepository.save(order);
        return OrderResponse.from(order);
    }

    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + orderId));
        return OrderResponse.from(order);
    }
}

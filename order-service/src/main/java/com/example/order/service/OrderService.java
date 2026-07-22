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
        // 대기열 모드가 아닌 상품은 waiting-room-service가 항상 valid=true로 응답하므로
        // 평소 주문 흐름에는 사실상 아무 영향이 없다.
        if (!waitingRoomClient.validate(request.productId(), request.queueToken())) {
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

        boolean reserved = inventoryClient.reserve(request.productId(), request.quantity());
        if (!reserved) {
            log.info("Order {} cancelled: out of stock", order.getId());
            order.cancel("OUT_OF_STOCK");
            orderRepository.save(order);
            return OrderResponse.from(order);
        }

        String idempotencyKey = "order-" + order.getId();
        PaymentResult paymentResult = paymentClient.pay(order.getId(), amount, idempotencyKey, request.simulateFailure());

        if (paymentResult.isApproved()) {
            order.confirm();
            log.info("Order {} confirmed", order.getId());
        } else {
            inventoryClient.restore(request.productId(), request.quantity());
            order.cancel("PAYMENT_FAILED");
            log.info("Order {} cancelled: payment failed, stock restored", order.getId());
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

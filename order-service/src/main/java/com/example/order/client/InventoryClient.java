package com.example.order.client;

import com.example.order.dto.ProductDto;
import com.example.order.dto.QuantityRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class InventoryClient {

    private final RestClient restClient;

    public InventoryClient(@Qualifier("inventoryRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public ProductDto getProduct(Long productId) {
        return restClient.get()
            .uri("/inventory/products/{id}", productId)
            .retrieve()
            .body(ProductDto.class);
    }

    /** 재고 확인 + 차감. 재고 부족(409)이면 false를 반환한다. */
    public boolean reserve(Long productId, int quantity, Long orderId) {
        try {
            restClient.post()
                .uri("/inventory/{id}/reserve", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", orderId.toString())
                .body(new QuantityRequest(quantity))
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.Conflict e) {
            return false;
        }
    }

    /** Saga 보상 트랜잭션: 결제 실패 시 차감했던 재고를 복구한다. */
    public void restore(Long productId, int quantity) {
        restClient.post()
            .uri("/inventory/{id}/restore", productId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new QuantityRequest(quantity))
            .retrieve()
            .toBodilessEntity();
    }
}

package com.example.order.client;

import com.example.order.dto.PaymentRequestDto;
import com.example.order.dto.PaymentResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PaymentClient {

    private final RestClient restClient;

    public PaymentClient(@Qualifier("paymentRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public PaymentResult pay(Long orderId, Long amount, String idempotencyKey, boolean simulateFailure) {
        return restClient.post()
            .uri("/payments")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new PaymentRequestDto(orderId, amount, idempotencyKey, simulateFailure))
            .retrieve()
            .body(PaymentResult.class);
    }
}

package com.example.order.client;

import com.example.order.dto.QueueValidateResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WaitingRoomClient {

    private final RestClient restClient;

    public WaitingRoomClient(@Qualifier("waitingRoomRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 상품이 대기열 모드가 아니면 항상 valid=true. 대기열 모드면 토큰을 검증하고,
     * 성공한 토큰은 waiting-room-service 쪽에서 즉시 폐기되어 재사용할 수 없다.
     */
    public boolean validate(Long productId, String queueToken) {
        QueueValidateResponse response = restClient.post()
            .uri("/queue/{productId}/validate", productId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ValidateRequestBody(queueToken))
            .retrieve()
            .body(QueueValidateResponse.class);
        return response != null && response.valid();
    }

    private record ValidateRequestBody(String token) {
    }
}

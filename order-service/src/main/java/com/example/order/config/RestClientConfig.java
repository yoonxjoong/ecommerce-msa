package com.example.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient inventoryRestClient(@Value("${clients.inventory-service.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public RestClient paymentRestClient(@Value("${clients.payment-service.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public RestClient waitingRoomRestClient(@Value("${clients.waiting-room-service.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}

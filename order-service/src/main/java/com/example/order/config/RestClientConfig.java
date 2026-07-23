package com.example.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * 타임아웃 없이 응답을 무한정 기다리면, Circuit Breaker가 실패로 카운트할 기회조차
     * 없이(예외가 안 터지니까) 스레드가 그대로 발이 묶인다. connect/read timeout을 걸어야
     * "응답 없이 멈춘 상태"도 결국 예외로 터져서 Circuit Breaker의 실패율 계산에 들어간다.
     */
    private ClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(3_000);
        return factory;
    }

    @Bean
    public RestClient inventoryRestClient(@Value("${clients.inventory-service.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutRequestFactory()).build();
    }

    @Bean
    public RestClient paymentRestClient(@Value("${clients.payment-service.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutRequestFactory()).build();
    }

    @Bean
    public RestClient waitingRoomRestClient(@Value("${clients.waiting-room-service.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(timeoutRequestFactory()).build();
    }
}

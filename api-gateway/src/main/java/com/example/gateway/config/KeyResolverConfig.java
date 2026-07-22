package com.example.gateway.config;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * RequestRateLimiter가 "누구 기준으로 카운트를 셀지" 결정하는 KeyResolver.
 * 지금은 클라이언트 IP 단위로 제한한다 (로그인 붙으면 사용자 ID 기준으로 바꾸는 게 더 맞다).
 */
@Configuration
public class KeyResolverConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            String ip = remoteAddress != null && remoteAddress.getAddress() != null
                ? remoteAddress.getAddress().getHostAddress()
                : "unknown";
            return Mono.just(ip);
        };
    }
}

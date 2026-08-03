package com.example.waitingroom.service;

import com.example.waitingroom.dto.EnterQueueResponse;
import com.example.waitingroom.dto.StatusResponse;
import com.example.waitingroom.dto.ValidateResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 가상 대기열. 상품별로 "지금 대기열 모드인지"를 Redis Set(queue:active-products)으로 토글하고,
 * 대기열 모드인 상품만 Redis Sorted Set(queue:waiting:{productId})에 순서대로 쌓아둔다.
 * 스케줄러가 주기적으로 앞쪽 일부를 "입장"시키고, 입장한 사람에게만 짧은 TTL의 토큰을 준다.
 * 대기열 모드가 아닌 상품은 이 전체 로직을 건너뛰고 항상 통과된다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QueueService {

    private static final String ACTIVE_PRODUCTS_KEY = "queue:active-products";

    private final StringRedisTemplate redisTemplate;

    @Value("${waiting-room.token-ttl-seconds:300}")
    private long tokenTtlSeconds;

    @Value("${waiting-room.admission.batch-size:2}")
    private long admissionBatchSize;

    private String waitingKey(Long productId) {
        return "queue:waiting:" + productId;
    }

    private String ticketTokenKey(Long productId, String ticketId) {
        return "queue:token:" + productId + ":" + ticketId;
    }

    private String validTokenKey(Long productId, String token) {
        return "queue:validtoken:" + productId + ":" + token;
    }

    public boolean isGated(Long productId) {
        Boolean isMember = redisTemplate.opsForSet().isMember(ACTIVE_PRODUCTS_KEY, String.valueOf(productId));
        return Boolean.TRUE.equals(isMember);
    }

    public void enableQueue(Long productId) {
        redisTemplate.opsForSet().add(ACTIVE_PRODUCTS_KEY, String.valueOf(productId));
        log.info("상품 {} 대기열 모드 활성화", productId);
    }

    public void disableQueue(Long productId) {
        redisTemplate.opsForSet().remove(ACTIVE_PRODUCTS_KEY, String.valueOf(productId));
        log.info("상품 {} 대기열 모드 비활성화", productId);
    }

    public EnterQueueResponse enter(Long productId) {
        if (!isGated(productId)) {
            return EnterQueueResponse.notGated();
        }
        String ticketId = UUID.randomUUID().toString();
        redisTemplate.opsForZSet().add(waitingKey(productId), ticketId, System.currentTimeMillis());
        long position = rank(productId, ticketId) + 1;
        return EnterQueueResponse.queued(ticketId, position);
    }

    public StatusResponse status(Long productId, String ticketId) {
        String token = redisTemplate.opsForValue().get(ticketTokenKey(productId, ticketId));
        if (token != null) {
            return new StatusResponse(true, null, token);
        }
        long position = rank(productId, ticketId) + 1;
        return new StatusResponse(false, position, null);
    }

    public ValidateResponse validate(Long productId, String token) {
        if (!isGated(productId)) {
            return ValidateResponse.ok();
        }
        if (token == null || token.isBlank()) {
            log.info("상품 {} 대기열 검증 실패: 토큰 없음", productId);
            return ValidateResponse.fail("NO_TOKEN");
        }
        // 검증에 성공하면 즉시 삭제해서 같은 토큰을 두 번 못 쓰게 한다 (1회용 입장권)
        Boolean deleted = redisTemplate.delete(validTokenKey(productId, token));
        if (Boolean.TRUE.equals(deleted)) {
            return ValidateResponse.ok();
        }
        log.info("상품 {} 대기열 검증 실패: 유효하지 않거나 만료/이미 사용된 토큰", productId);
        return ValidateResponse.fail("INVALID_OR_EXPIRED_TOKEN");
    }

    @Scheduled(fixedDelayString = "${waiting-room.admission.interval-ms:3000}")
    public void admitNextBatch() {
        Set<String> activeProducts = redisTemplate.opsForSet().members(ACTIVE_PRODUCTS_KEY);
        if (activeProducts == null || activeProducts.isEmpty()) {
            return;
        }
        for (String productIdStr : activeProducts) {
            admitFor(Long.valueOf(productIdStr));
        }
    }

    private void admitFor(Long productId) {
        Set<ZSetOperations.TypedTuple<String>> popped =
            redisTemplate.opsForZSet().popMin(waitingKey(productId), admissionBatchSize);
        if (popped == null || popped.isEmpty()) {
            return;
        }
        for (ZSetOperations.TypedTuple<String> tuple : popped) {
            String ticketId = tuple.getValue();
            String token = UUID.randomUUID().toString();
            Duration ttl = Duration.ofSeconds(tokenTtlSeconds);
            redisTemplate.opsForValue().set(ticketTokenKey(productId, ticketId), token, ttl);
            redisTemplate.opsForValue().set(validTokenKey(productId, token), "1", ttl);
            log.info("상품 {} 티켓 {} 입장 허가, 토큰 발급", productId, ticketId);
        }
    }

    private long rank(Long productId, String ticketId) {
        Long rank = redisTemplate.opsForZSet().rank(waitingKey(productId), ticketId);
        return rank == null ? -1 : rank;
    }
}

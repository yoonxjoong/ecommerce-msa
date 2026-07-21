package com.example.inventory.service;

import com.example.inventory.repository.ProductRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final String STOCK_KEY_PREFIX = "stock:product:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> decreaseStockScript;
    private final RedisScript<Long> increaseStockScript;
    private final ProductRepository productRepository;

    /**
     * data.sql 시딩과의 순서 경쟁을 피하려고 @PostConstruct 대신 ApplicationReadyEvent에서 실행한다.
     * 컨테이너 재시작 시 이미 진행 중이던 재고 차감을 덮어쓰지 않도록,
     * 키가 없을 때만(SETNX) DB의 초기 재고로 Redis 카운터를 채운다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedStockCounters() {
        productRepository.findAll().forEach(product -> {
            String key = STOCK_KEY_PREFIX + product.getId();
            redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(product.getStockQuantity()));
        });
    }

    public boolean reserve(Long productId, int quantity) {
        String key = STOCK_KEY_PREFIX + productId;
        Long result = redisTemplate.execute(decreaseStockScript, List.of(key), String.valueOf(quantity));
        return result != null && result == 1L;
    }

    public void restore(Long productId, int quantity) {
        String key = STOCK_KEY_PREFIX + productId;
        redisTemplate.execute(increaseStockScript, List.of(key), String.valueOf(quantity));
    }

    public long getAvailableStock(Long productId) {
        String value = redisTemplate.opsForValue().get(STOCK_KEY_PREFIX + productId);
        return value == null ? 0 : Long.parseLong(value);
    }
}

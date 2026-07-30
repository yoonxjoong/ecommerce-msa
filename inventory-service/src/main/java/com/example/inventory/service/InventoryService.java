package com.example.inventory.service;

import com.example.inventory.domain.Product;
import com.example.inventory.repository.ProductRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryService {

    private static final String STOCK_KEY_PREFIX = "stock:product:";
    private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:reserve:";

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

    public boolean reserve(Long productId, int quantity, String idempotencyKey) {
        String stockKey = STOCK_KEY_PREFIX + productId;
        String idempotencyRedisKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
        Long result = redisTemplate.execute(decreaseStockScript, List.of(stockKey, idempotencyRedisKey), String.valueOf(quantity));
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

    /**
     * Cache-Aside: 캐시에 없으면 DB에서 읽어와 채워 넣고, 있으면 DB를 안 거친다.
     * 여기 캐시되는 stockQuantity는 최초 시딩값 기준이라 실시간 재고가 아니다 —
     * 실시간 재고 확인은 캐시를 타지 않는 getAvailableStock()/재고 확인 API를 써야 한다.
     * 이 데모엔 상품 정보를 바꾸는 API가 없어서 캐시 무효화(evict) 로직은 두지 않았다.
     */
    @Cacheable(value = "product", key = "#productId", unless = "#result == null")
    public Product getProduct(Long productId) {
        log.info("[Cache Miss] DB에서 상품 {} 조회", productId);
        return productRepository.findById(productId).orElse(null);
    }

    @Cacheable(value = "productList")
    public List<Product> getAllProducts() {
        log.info("[Cache Miss] DB에서 전체 상품 목록 조회");
        return productRepository.findAll();
    }
}

package com.example.reconciliation.job;

import com.example.reconciliation.domain.Product;
import com.example.reconciliation.repository.ProductRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis 재고 카운터(진짜 실시간 값)를 주기적으로 Postgres product.stock_quantity에 되돌려 쓴다.
 *
 * 이게 없으면 Redis가 데이터를 잃었을 때(재시작, 장애) inventory-service의
 * seedStockCounters()가 DB의 "최초 시딩값"으로 되돌아가버려서, 이미 판매된 재고가
 * 되살아나는 오버셀링 사고로 이어진다. 이 배치가 주기적으로 DB를 최신 값에 가깝게
 * 맞춰두면, 최악의 경우에도 "최초값"이 아니라 "마지막으로 동기화된 값"으로 복구된다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StockReconciliationJob {

    private static final String STOCK_KEY_PATTERN = "stock:product:*";
    private static final String STOCK_KEY_PREFIX = "stock:product:";

    private final StringRedisTemplate redisTemplate;
    private final ProductRepository productRepository;

    @Scheduled(fixedDelayString = "${reconciliation.fixed-delay-ms:5000}")
    @Transactional
    public void reconcile() {
        Set<String> keys = redisTemplate.keys(STOCK_KEY_PATTERN);
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String key : keys) {
            reconcileOne(key);
        }
    }

    private void reconcileOne(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return;
        }

        Long productId = Long.valueOf(key.substring(STOCK_KEY_PREFIX.length()));
        int redisStock = Integer.parseInt(value);

        productRepository.findById(productId).ifPresent(product -> {
            if (product.needsUpdate(redisStock)) {
                int before = product.getStockQuantity();
                product.syncStockQuantity(redisStock);
                productRepository.save(product);
                log.info("Reconciled product {} stock: {} -> {}", productId, before, redisStock);
            }
        });
    }
}

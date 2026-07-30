package com.example.inventory.controller;

import com.example.inventory.domain.Product;
import com.example.inventory.dto.ReserveRequest;
import com.example.inventory.dto.RestoreRequest;
import com.example.inventory.dto.StockResponse;
import com.example.inventory.service.InventoryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    /** 상품 목록 - Cache-Aside (30초 TTL), 캐시 히트 시 DB를 안 거침 */
    @GetMapping("/products")
    public List<Product> products() {
        return inventoryService.getAllProducts();
    }

    /** 상품 상세 - Cache-Aside (30초 TTL). 여기 포함된 stockQuantity는 실시간 값이 아님 */
    @GetMapping("/products/{productId}")
    public ResponseEntity<Product> product(@PathVariable Long productId) {
        Product product = inventoryService.getProduct(productId);
        return product != null ? ResponseEntity.ok(product) : ResponseEntity.notFound().build();
    }

    @GetMapping("/products/{productId}/stock")
    public StockResponse stock(@PathVariable Long productId) {
        return new StockResponse(productId, inventoryService.getAvailableStock(productId));
    }

    /** 재고 확인 + 차감을 Redis Lua 스크립트로 원자적으로 처리 (오버셀링 방지) */
    @PostMapping("/{productId}/reserve")
    public ResponseEntity<Void> reserve(
            @PathVariable Long productId,
            @RequestBody @Valid ReserveRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        boolean reserved = inventoryService.reserve(productId, request.quantity(), idempotencyKey);
        if (!reserved) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.ok().build();
    }

    /** Saga 보상 트랜잭션: 결제 실패 시 차감했던 재고를 복구 */
    @PostMapping("/{productId}/restore")
    public ResponseEntity<Void> restore(@PathVariable Long productId, @RequestBody @Valid RestoreRequest request) {
        inventoryService.restore(productId, request.quantity());
        return ResponseEntity.ok().build();
    }
}

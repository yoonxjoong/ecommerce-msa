package com.example.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * inventory-service의 product 테이블을 그대로 바라보는 읽기/갱신 전용 엔티티.
 * 스키마 소유권은 inventory-service에 있으므로 이 모듈은 ddl-auto: none으로 스키마를 건드리지 않는다.
 */
@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor
public class Product {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Long price;

    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity;

    public boolean needsUpdate(int redisStock) {
        return !this.stockQuantity.equals(redisStock);
    }

    public void syncStockQuantity(int redisStock) {
        this.stockQuantity = redisStock;
    }
}

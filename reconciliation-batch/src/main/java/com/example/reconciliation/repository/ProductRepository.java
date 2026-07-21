package com.example.reconciliation.repository;

import com.example.reconciliation.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}

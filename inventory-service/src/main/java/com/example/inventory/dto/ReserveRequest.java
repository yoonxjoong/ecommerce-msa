package com.example.inventory.dto;

import jakarta.validation.constraints.Positive;

public record ReserveRequest(@Positive int quantity) {
}

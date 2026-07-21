package com.example.inventory.dto;

import jakarta.validation.constraints.Positive;

public record RestoreRequest(@Positive int quantity) {
}

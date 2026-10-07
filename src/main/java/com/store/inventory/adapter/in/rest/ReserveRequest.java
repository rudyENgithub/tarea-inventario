package com.store.inventory.adapter.in.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ReserveRequest(
        @NotBlank @Size(max = 64) String orderId,
        @NotBlank @Size(max = 64) String sku,
        @Positive int quantity) {
}

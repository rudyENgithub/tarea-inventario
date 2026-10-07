package com.store.inventory.adapter.in.rest;

import com.store.inventory.api.ProductCategory;
import jakarta.validation.constraints.NotNull;

public record RegisterProductRequest(@NotNull ProductCategory category) {
}

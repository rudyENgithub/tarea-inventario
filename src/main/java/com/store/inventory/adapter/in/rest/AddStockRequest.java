package com.store.inventory.adapter.in.rest;

import jakarta.validation.constraints.Positive;

public record AddStockRequest(@Positive int quantity) {
}

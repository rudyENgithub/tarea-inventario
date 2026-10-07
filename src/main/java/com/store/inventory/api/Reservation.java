package com.store.inventory.api;

import java.time.Instant;

/**
 * No modificar este archivo.
 */
public record Reservation(String orderId, String sku, int quantity, Instant expiresAt) {
}

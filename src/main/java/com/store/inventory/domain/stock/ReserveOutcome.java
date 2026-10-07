package com.store.inventory.domain.stock;

/**
 * Resultado de {@link ProductStock#reserve}. {@code created} es false cuando la llamada fue una repetición idempotente.
 */
public record ReserveOutcome(StockReservation reservation, boolean created) {
}

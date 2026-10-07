package com.store.inventory.application.port.out;

import com.store.inventory.domain.alert.LowStockAlert;

/**
 * Una entrada del outbox reclamada para su entrega.
 *
 * @param attempts intentos de entrega incluyendo el actual ({@code >= 1} al reclamarla)
 */
public record PendingAlert(long id, LowStockAlert alert, int attempts) {
}

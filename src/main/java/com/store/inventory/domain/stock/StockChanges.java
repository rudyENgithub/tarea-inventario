package com.store.inventory.domain.stock;

import java.util.List;
import java.util.Set;

/**
 * Cambios de reservas acumulados por una operación de {@link ProductStock}. Los adaptadores aplican primero
 * {@code removedOrderIds} y luego {@code upserts}. Un id de orden nunca aparece en ambos.
 */
public record StockChanges(List<StockReservation> upserts, Set<String> removedOrderIds) {

    public StockChanges {
        upserts = List.copyOf(upserts);
        removedOrderIds = Set.copyOf(removedOrderIds);
    }

    public static StockChanges none() {
        return new StockChanges(List.of(), Set.of());
    }

    public boolean isEmpty() {
        return upserts.isEmpty() && removedOrderIds.isEmpty();
    }
}

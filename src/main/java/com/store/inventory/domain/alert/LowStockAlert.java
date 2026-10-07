package com.store.inventory.domain.alert;

import java.time.Instant;

/** Hecho de dominio: un producto alcanzó el umbral de stock bajo. La entrega no es una responsabilidad del dominio. */
public record LowStockAlert(String sku, int availableUnits, Instant occurredAt) {

    public LowStockAlert {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (availableUnits < 0) {
            throw new IllegalArgumentException("availableUnits must not be negative: " + availableUnits);
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}

package com.store.inventory.application.port.out;

import com.store.inventory.domain.alert.LowStockAlert;
import java.util.List;

/**
 * Resultado de una {@link StockOperation}: el valor para el llamador más las alertas que deben registrarse de forma
 * atómica con el cambio de estado.
 */
public record OperationResult<R>(R value, List<LowStockAlert> alerts) {

    public OperationResult {
        alerts = List.copyOf(alerts);
    }

    public static <R> OperationResult<R> of(R value) {
        return new OperationResult<>(value, List.of());
    }
}

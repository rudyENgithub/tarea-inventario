package com.store.inventory.application.port.out;

import com.store.inventory.domain.stock.ProductStock;

/**
 * Una unidad de trabajo ejecutada dentro de la sección crítica por SKU. Debe ser corta y no debe llamar a sistemas
 * externos ni bloquearse.
 */
@FunctionalInterface
public interface StockOperation<R> {

    OperationResult<R> apply(ProductStock product);
}

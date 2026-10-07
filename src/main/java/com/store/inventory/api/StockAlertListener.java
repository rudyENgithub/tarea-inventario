package com.store.inventory.api;

/**
 * Recibe alertas de stock bajo. No modificar este archivo.
 */
public interface StockAlertListener {

    void onLowStock(String sku, int availableUnits);
}

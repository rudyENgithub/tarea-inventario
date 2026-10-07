package com.store.inventory.api;

/**
 * No modificar este archivo.
 */
public class OrderLimitExceededException extends RuntimeException {

    public OrderLimitExceededException(String sku, int requested, int limit) {
        super("Order limit for " + sku + " is " + limit + " units, requested " + requested);
    }
}

package com.store.inventory.application.port.out;

/** Un id de orden ya está ligado a un SKU distinto (decisión D6). Los ids de orden son globalmente únicos. */
public class OrderIdConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String orderId;

    public OrderIdConflictException(String orderId) {
        super("Order " + orderId + " is already bound to another SKU");
        this.orderId = orderId;
    }

    public String orderId() {
        return orderId;
    }
}

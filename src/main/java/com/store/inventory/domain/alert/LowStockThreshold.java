package com.store.inventory.domain.alert;

/** Unidades iguales o inferiores a las cuales un producto se considera con stock bajo. */
public record LowStockThreshold(int units) {

    public static final LowStockThreshold DEFAULT = new LowStockThreshold(5);

    public LowStockThreshold {
        if (units < 0) {
            throw new IllegalArgumentException("threshold must not be negative: " + units);
        }
    }

    public boolean isLow(int available) {
        return available <= units;
    }
}

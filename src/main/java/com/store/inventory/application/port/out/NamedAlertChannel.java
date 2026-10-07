package com.store.inventory.application.port.out;

import com.store.inventory.api.StockAlertListener;
import java.util.Objects;

/** Un {@link StockAlertListener} con un nombre de canal. Se crea mediante {@link AlertChannel#of}. */
final class NamedAlertChannel implements AlertChannel {

    private final String name;
    private final StockAlertListener delegate;

    NamedAlertChannel(String name, StockAlertListener delegate) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("channel name must not be blank");
        }
        this.name = name;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        delegate.onLowStock(sku, availableUnits);
    }
}

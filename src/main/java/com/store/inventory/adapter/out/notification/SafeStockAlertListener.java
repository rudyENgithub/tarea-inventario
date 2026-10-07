package com.store.inventory.adapter.out.notification;

import com.store.inventory.application.port.out.AlertChannel;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorador que contiene los fallos de un canal de mejor esfuerzo: las {@link RuntimeException} se registran en el
 * log, se reportan a un {@link FailureObserver} y nunca se propagan. Los {@link Error} y la bandera de interrupción
 * del hilo no se tocan.
 */
public final class SafeStockAlertListener implements AlertChannel {

    /** Punto de extensión para métricas; se invoca una vez por cada fallo absorbido. */
    @FunctionalInterface
    public interface FailureObserver {
        void onFailure(String channelName, String sku, int availableUnits, Throwable failure);
    }

    private static final Logger LOG = LoggerFactory.getLogger(SafeStockAlertListener.class);

    private final AlertChannel delegate;
    private final FailureObserver observer;

    public SafeStockAlertListener(AlertChannel delegate, FailureObserver observer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public SafeStockAlertListener(AlertChannel delegate) {
        this(delegate, (channel, sku, available, failure) -> { });
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        try {
            delegate.onLowStock(sku, availableUnits);
        } catch (RuntimeException e) {
            LOG.warn("Alert channel {} failed for sku={} available={}", delegate.name(), sku, availableUnits, e);
            notifyObserver(sku, availableUnits, e);
        }
    }

    private void notifyObserver(String sku, int availableUnits, RuntimeException failure) {
        try {
            observer.onFailure(delegate.name(), sku, availableUnits, failure);
        } catch (RuntimeException observerFailure) {
            LOG.warn("Failure observer of channel {} threw", delegate.name(), observerFailure);
        }
    }
}

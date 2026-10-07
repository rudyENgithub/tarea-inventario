package com.store.inventory.adapter.out.notification;

import com.store.inventory.application.port.out.AlertChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Escribe la alerta en el log de la aplicación. Canal de mejor esfuerzo, siempre habilitado. */
public final class LoggingAlertChannel implements AlertChannel {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingAlertChannel.class);

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        LOG.warn("LOW_STOCK sku={} available={}", sku, availableUnits);
    }
}

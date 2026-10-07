package com.store.inventory.application.port.out;

import com.store.inventory.api.StockAlertListener;

/**
 * Una forma de entregar una alerta de stock bajo (log, email, ...). Todo canal es un {@link StockAlertListener}, por lo
 * que los canales son intercambiables donde se espere un listener.
 */
public interface AlertChannel extends StockAlertListener {

    /** Identificador estable y no vacío usado en logs y métricas. */
    String name();

    /** Adapta cualquier listener a un canal con nombre. */
    static AlertChannel of(String name, StockAlertListener delegate) {
        return new NamedAlertChannel(name, delegate);
    }
}

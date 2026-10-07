package com.store.inventory.application.alert;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entrega las alertas pendientes en el hilo del llamador. Se usa en el modo en memoria; nunca propaga fallos. */
public final class SynchronousAlertDispatchTrigger implements AlertDispatchTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(SynchronousAlertDispatchTrigger.class);

    private final AlertDispatcher dispatcher;

    public SynchronousAlertDispatchTrigger(AlertDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public void alertsEnqueued() {
        try {
            dispatcher.dispatchPending();
        } catch (RuntimeException e) {
            LOG.warn("Synchronous alert dispatch failed", e);
        }
    }
}

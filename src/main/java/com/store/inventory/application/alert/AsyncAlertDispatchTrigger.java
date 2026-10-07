package com.store.inventory.application.alert;

import java.util.Objects;

/** Disparador asíncrono: despierta al {@link AlertRelay}, que entrega las alertas fuera del hilo del llamador. */
public final class AsyncAlertDispatchTrigger implements AlertDispatchTrigger {

    private final AlertRelay relay;

    public AsyncAlertDispatchTrigger(AlertRelay relay) {
        this.relay = Objects.requireNonNull(relay, "relay");
    }

    @Override
    public void alertsEnqueued() {
        relay.signal();
    }
}

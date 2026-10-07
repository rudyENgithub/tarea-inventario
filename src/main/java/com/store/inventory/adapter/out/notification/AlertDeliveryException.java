package com.store.inventory.adapter.out.notification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Uno o más canales fallaron al entregar una alerta. Lleva cada fallo por nombre de canal. */
public class AlertDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Map<String, Throwable> failuresByChannel;

    public AlertDeliveryException(Map<String, Throwable> failuresByChannel) {
        super("Delivery failed for channels: " + failuresByChannel.keySet());
        this.failuresByChannel = Collections.unmodifiableMap(new LinkedHashMap<>(failuresByChannel));
        failuresByChannel.values().forEach(this::addSuppressed);
    }

    public Map<String, Throwable> failuresByChannel() {
        return failuresByChannel;
    }
}

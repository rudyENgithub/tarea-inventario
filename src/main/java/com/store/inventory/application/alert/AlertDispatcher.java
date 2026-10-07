package com.store.inventory.application.alert;

import com.store.inventory.api.StockAlertListener;
import com.store.inventory.application.port.out.AlertOutbox;
import com.store.inventory.application.port.out.PendingAlert;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vacía el {@link AlertOutbox} hacia un {@link StockAlertListener} con reintentos (entrega al menos una vez).
 *
 * <p>Una entrada se da por entregada solo cuando el listener retorna con normalidad; una {@link RuntimeException}
 * programa un reintento o marca la entrada como fallida de forma definitiva. Cada entrada se procesa de forma
 * aislada. Un {@link Error} se propaga sin marcar la entrada, por lo que vuelve a ser reclamable cuando vence su
 * lease. Es seguro invocarlo desde muchos hilos: la exclusión la aporta {@link AlertOutbox#claimDue}.
 */
public final class AlertDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(AlertDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 1_000;

    private final AlertOutbox outbox;
    private final StockAlertListener listener;
    private final Clock clock;
    private final AlertRetryPolicy retryPolicy;
    private final int batchSize;
    private final Duration lease;

    public AlertDispatcher(AlertOutbox outbox, StockAlertListener listener, Clock clock, AlertRetryPolicy retryPolicy,
            int batchSize, Duration lease) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1: " + batchSize);
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive: " + lease);
        }
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.batchSize = batchSize;
        this.lease = lease;
    }

    /**
     * Entrega todas las alertas pendientes de envío.
     *
     * @return cuántas alertas se entregaron con éxito
     */
    public int dispatchPending() {
        int delivered = 0;
        List<PendingAlert> batch = claim();
        while (!batch.isEmpty()) {
            for (PendingAlert pending : batch) {
                if (deliver(pending)) {
                    delivered++;
                }
            }
            batch = claim();
        }
        return delivered;
    }

    private List<PendingAlert> claim() {
        return outbox.claimDue(clock.instant(), batchSize, lease);
    }

    private boolean deliver(PendingAlert pending) {
        try {
            listener.onLowStock(pending.alert().sku(), pending.alert().availableUnits());
        } catch (RuntimeException failure) {
            recordFailure(pending, failure);
            return false;
        }
        recordDelivery(pending);
        return true;
    }

    private void recordDelivery(PendingAlert pending) {
        try {
            outbox.markDelivered(pending.id(), clock.instant());
        } catch (RuntimeException e) {
            LOG.error("Could not mark alert {} (sku={}) as delivered; it will be redelivered after its lease",
                    pending.id(), pending.alert().sku(), e);
            throw e;
        }
    }

    private void recordFailure(PendingAlert pending, RuntimeException failure) {
        String error = describe(failure);
        Instant now = clock.instant();
        try {
            Optional<Duration> delay = retryPolicy.delayAfter(pending.attempts());
            if (delay.isPresent()) {
                LOG.warn("Alert {} (sku={}) failed on attempt {}; retrying in {}", pending.id(),
                        pending.alert().sku(), pending.attempts(), delay.get(), failure);
                outbox.markRetry(pending.id(), now.plus(delay.get()), error);
            } else {
                LOG.error("Alert {} (sku={}) failed permanently after {} attempts", pending.id(),
                        pending.alert().sku(), pending.attempts(), failure);
                outbox.markFailed(pending.id(), error);
            }
        } catch (RuntimeException e) {
            LOG.error("Could not record the failure of alert {}; it will be reclaimed after its lease",
                    pending.id(), e);
            throw e;
        }
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage() == null ? failure.getClass().getName()
                : failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}

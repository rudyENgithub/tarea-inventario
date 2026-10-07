package com.store.inventory.adapter.out.persistence.memory;

import com.store.inventory.application.port.out.AlertOutbox;
import com.store.inventory.application.port.out.PendingAlert;
import com.store.inventory.domain.alert.LowStockAlert;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.concurrent.locks.ReentrantLock;

/** {@link AlertOutbox} en memoria y thread-safe. Un único lock mantiene la lógica de reclamo trivialmente correcta. */
public final class InMemoryAlertOutbox implements AlertOutbox {

    public enum Status { PENDING, IN_PROGRESS, DELIVERED, FAILED }

    /** Instantánea inmutable de una entrada, para pruebas y diagnóstico. */
    public record Entry(long id, LowStockAlert alert, Status status, int attempts, String lastError) {
    }

    private static final int MAX_ERROR_LENGTH = 1_000;

    private static final class Slot {
        private final long id;
        private final LowStockAlert alert;
        private Status status = Status.PENDING;
        private Instant dueAt;
        private int attempts;
        private String lastError;

        private Slot(long id, LowStockAlert alert) {
            this.id = id;
            this.alert = alert;
            this.dueAt = alert.occurredAt();
        }

        private boolean isTerminal() {
            return status == Status.DELIVERED || status == Status.FAILED;
        }

        private boolean isDueAt(Instant now) {
            return (status == Status.PENDING || status == Status.IN_PROGRESS) && !dueAt.isAfter(now);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, Slot> slots = new TreeMap<>();
    private long lastId;

    /**
     * Agrega una entrada {@code PENDING} vencida en {@code alert.occurredAt()} y devuelve su id (empezando en 1).
     */
    public long enqueue(LowStockAlert alert) {
        Objects.requireNonNull(alert, "alert");
        lock.lock();
        try {
            long id = ++lastId;
            slots.put(id, new Slot(id, alert));
            return id;
        } finally {
            lock.unlock();
        }
    }

    public List<Entry> entries() {
        lock.lock();
        try {
            List<Entry> snapshot = new ArrayList<>(slots.size());
            for (Slot slot : slots.values()) {
                snapshot.add(new Entry(slot.id, slot.alert, slot.status, slot.attempts, slot.lastError));
            }
            return List.copyOf(snapshot);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<PendingAlert> claimDue(Instant now, int limit, Duration lease) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1: " + limit);
        }
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive: " + lease);
        }
        lock.lock();
        try {
            List<PendingAlert> claimed = new ArrayList<>();
            for (Slot slot : slots.values()) {
                if (claimed.size() == limit) {
                    break;
                }
                if (slot.isDueAt(now)) {
                    slot.status = Status.IN_PROGRESS;
                    slot.dueAt = now.plus(lease);
                    slot.attempts++;
                    claimed.add(new PendingAlert(slot.id, slot.alert, slot.attempts));
                }
            }
            return List.copyOf(claimed);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void markDelivered(long id, Instant now) {
        update(id, slot -> slot.status = Status.DELIVERED);
    }

    @Override
    public void markRetry(long id, Instant nextAttemptAt, String error) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        update(id, slot -> {
            slot.status = Status.PENDING;
            slot.dueAt = nextAttemptAt;
            slot.lastError = truncate(error);
        });
    }

    @Override
    public void markFailed(long id, String error) {
        update(id, slot -> {
            slot.status = Status.FAILED;
            slot.lastError = truncate(error);
        });
    }

    private void update(long id, Consumer<Slot> change) {
        lock.lock();
        try {
            Slot slot = slots.get(id);
            if (slot != null && !slot.isTerminal()) {
                change.accept(slot);
            }
        } finally {
            lock.unlock();
        }
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}

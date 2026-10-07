package com.store.inventory.testsupport;

import com.store.inventory.api.StockAlertListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Registra alertas; opcionalmente falla después de registrar. Permite esperar sin dormir. */
public final class RecordingAlertListener implements StockAlertListener {

    public record Alert(String sku, int available) {
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final List<Alert> alerts = new ArrayList<>();
    private final RuntimeException failure;

    public RecordingAlertListener() {
        this(null);
    }

    private RecordingAlertListener(RuntimeException failure) {
        this.failure = failure;
    }

    /** Un listener que registra cada alerta y luego lanza la excepción indicada. */
    public static RecordingAlertListener failing(RuntimeException failure) {
        return new RecordingAlertListener(failure);
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        lock.lock();
        try {
            alerts.add(new Alert(sku, availableUnits));
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        if (failure != null) {
            throw failure;
        }
    }

    public List<Alert> alerts() {
        lock.lock();
        try {
            return List.copyOf(alerts);
        } finally {
            lock.unlock();
        }
    }

    public int count() {
        lock.lock();
        try {
            return alerts.size();
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            alerts.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Espera hasta que se hayan registrado al menos {@code expectedCount} alertas. Devuelve si ocurrió a tiempo.
     */
    public boolean await(int expectedCount, Duration timeout) {
        long remaining = timeout.toNanos();
        lock.lock();
        try {
            while (alerts.size() < expectedCount) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = changed.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Atajo para pruebas: espera hasta 5 segundos. */
    public boolean await(int expectedCount) {
        return await(expectedCount, Duration.ofSeconds(5));
    }
}

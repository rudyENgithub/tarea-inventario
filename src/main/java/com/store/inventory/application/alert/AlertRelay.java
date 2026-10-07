package com.store.inventory.application.alert;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bucle en segundo plano sobre un hilo virtual que ejecuta una pasada de despacho de inmediato, cada vez que recibe
 * una señal y, como mínimo, cada {@code pollInterval} (para recoger reintentos y leases vencidos). Los fallos de una
 * pasada nunca matan el hilo.
 *
 * <p>Ciclo de vida: {@link #start()} una sola vez, {@link #close()} para detenerlo. La espera usa el reloj de pared
 * solo como mecánica de planificación; la hora de negocio siempre proviene del {@code Clock} del despachador.
 */
public final class AlertRelay implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AlertRelay.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FORCED_CLOSE_TIMEOUT = Duration.ofSeconds(2);

    private enum State { NEW, RUNNING, CLOSED }

    private final IntSupplier dispatchPass;
    private final Duration pollInterval;
    private final Semaphore signals = new Semaphore(0);
    private final ReentrantLock lifecycle = new ReentrantLock();
    private volatile boolean stopping;
    private State state = State.NEW;
    private Thread thread;

    public AlertRelay(IntSupplier dispatchPass, Duration pollInterval) {
        this.dispatchPass = Objects.requireNonNull(dispatchPass, "dispatchPass");
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
        this.pollInterval = pollInterval;
    }

    /** @throws IllegalStateException si el relay ya fue iniciado o cerrado */
    public void start() {
        lifecycle.lock();
        try {
            if (state != State.NEW) {
                throw new IllegalStateException("AlertRelay already " + state);
            }
            state = State.RUNNING;
            thread = Thread.ofVirtual().name("alert-relay").unstarted(this::loop);
            thread.start();
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Despierta al relay. Barato, agrupa señales, nunca lanza excepciones; inofensivo antes de {@link #start()} y no
     * hace nada tras cerrar.
     */
    public void signal() {
        if (!stopping && signals.availablePermits() == 0) {
            signals.release();
        }
    }

    public boolean isAlive() {
        lifecycle.lock();
        try {
            return thread != null && thread.isAlive();
        } finally {
            lifecycle.unlock();
        }
    }

    /** Idempotente. Espera a que termine la pasada en curso para que ninguna entrega quede a medias. */
    @Override
    public void close() {
        Thread toJoin;
        lifecycle.lock();
        try {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            stopping = true;
            signals.release();
            toJoin = thread;
        } finally {
            lifecycle.unlock();
        }
        if (toJoin != null) {
            awaitTermination(toJoin);
        }
    }

    private void awaitTermination(Thread relayThread) {
        try {
            if (relayThread.join(CLOSE_TIMEOUT)) {
                return;
            }
            LOG.warn("Alert relay did not stop within {}; interrupting it", CLOSE_TIMEOUT);
            relayThread.interrupt();
            relayThread.join(FORCED_CLOSE_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            relayThread.interrupt();
        }
    }

    private void loop() {
        while (!stopping) {
            runPass();
            if (stopping || !awaitSignal()) {
                return;
            }
        }
    }

    private void runPass() {
        try {
            dispatchPass.getAsInt();
        } catch (RuntimeException e) {
            LOG.warn("Alert dispatch pass failed; the relay keeps running", e);
        }
    }

    /** @return false cuando se interrumpe, por lo que el bucle debe terminar */
    private boolean awaitSignal() {
        try {
            signals.tryAcquire(pollInterval.toNanos(), TimeUnit.NANOSECONDS);
            signals.drainPermits();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

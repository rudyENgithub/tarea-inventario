package com.store.inventory.testsupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/** Ejecuta muchas tareas en hilos virtuales, liberándolas todas a la vez para maximizar la contención. */
public final class ConcurrentTestSupport {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Resultado de una tarea: un valor o la excepción que lanzó. */
    public record Outcome<T>(T value, Throwable failure) {

        public boolean succeeded() {
            return failure == null;
        }
    }

    private ConcurrentTestSupport() {
    }

    /**
     * Ejecuta {@code n} tareas de forma concurrente y devuelve sus resultados en el orden de las tareas.
     *
     * @throws AssertionError si las tareas no terminan en 10 segundos
     */
    public static <T> List<Outcome<T>> runConcurrently(int n, IntFunction<Callable<T>> taskFactory) {
        return runConcurrently(n, TIMEOUT, taskFactory);
    }

    public static <T> List<Outcome<T>> runConcurrently(int n, Duration timeout, IntFunction<Callable<T>> taskFactory) {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Callable<T> task = taskFactory.apply(i);
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>(n);
            long deadline = System.nanoTime() + timeout.toNanos();
            for (Future<T> future : futures) {
                outcomes.add(await(future, deadline, timeout));
            }
            return outcomes;
        }
    }

    /**
     * Consulta {@code condition} (durmiendo 1 ms entre verificaciones) hasta que se cumple; falla tras {@code timeout}.
     */
    public static void awaitCondition(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static <T> Outcome<T> await(Future<T> future, long deadlineNanos, Duration timeout) {
        try {
            return new Outcome<>(future.get(Math.max(1, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS), null);
        } catch (ExecutionException e) {
            return new Outcome<>(null, e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("Concurrent tasks did not finish within " + timeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrent tasks", e);
        }
    }
}

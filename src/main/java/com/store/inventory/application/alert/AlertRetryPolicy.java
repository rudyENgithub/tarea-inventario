package com.store.inventory.application.alert;

import java.time.Duration;
import java.util.Optional;

/**
 * Backoff exponencial para la entrega de alertas: {@code min(baseDelay * 2^(attempt-1), maxDelay)} hasta {@code
 * maxAttempts}.
 */
public record AlertRetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public AlertRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }
        if (baseDelay == null || baseDelay.isZero() || baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must be positive: " + baseDelay);
        }
        if (maxDelay == null || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= baseDelay: " + maxDelay);
        }
    }

    /** Un único intento: el primer fallo es definitivo. */
    public static AlertRetryPolicy noRetry() {
        return new AlertRetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    public static AlertRetryPolicy defaults() {
        return new AlertRetryPolicy(5, Duration.ofSeconds(30), Duration.ofMinutes(15));
    }

    /**
     * Espera antes del siguiente intento, o vacío cuando {@code attemptsMade} ya alcanzó {@code maxAttempts}.
     *
     * @param attemptsMade intentos realizados hasta ahora, incluido el que acaba de fallar ({@code >= 1})
     */
    public Optional<Duration> delayAfter(int attemptsMade) {
        if (attemptsMade < 1) {
            throw new IllegalArgumentException("attemptsMade must be >= 1: " + attemptsMade);
        }
        if (attemptsMade >= maxAttempts) {
            return Optional.empty();
        }
        Duration delay = baseDelay;
        Duration half = maxDelay.dividedBy(2);
        for (int attempt = 1; attempt < attemptsMade; attempt++) {
            if (delay.compareTo(half) > 0) {
                return Optional.of(maxDelay);
            }
            delay = delay.multipliedBy(2);
        }
        return Optional.of(delay.compareTo(maxDelay) > 0 ? maxDelay : delay);
    }
}

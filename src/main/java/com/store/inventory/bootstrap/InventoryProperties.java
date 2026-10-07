package com.store.inventory.bootstrap;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuración externa del inventario. Los valores inválidos hacen que la aplicación falle rápido al arrancar. */
@ConfigurationProperties("inventory")
public record InventoryProperties(
        @DefaultValue LowStock lowStock,
        @DefaultValue Alerts alerts,
        @DefaultValue Database database) {

    public record LowStock(@DefaultValue("5") int threshold) {

        public LowStock {
            if (threshold < 0) {
                throw new IllegalArgumentException("inventory.low-stock.threshold must be >= 0 but was " + threshold);
            }
        }
    }

    public record Alerts(@DefaultValue Relay relay, @DefaultValue Dispatch dispatch) {
    }

    public record Relay(@DefaultValue("5s") Duration pollInterval) {

        public Relay {
            requirePositive(pollInterval, "inventory.alerts.relay.poll-interval");
        }
    }

    public record Dispatch(
            @DefaultValue("50") int batchSize,
            @DefaultValue("2m") Duration lease,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("30s") Duration baseDelay,
            @DefaultValue("15m") Duration maxDelay) {

        public Dispatch {
            if (batchSize < 1) {
                throw new IllegalArgumentException("inventory.alerts.dispatch.batch-size must be >= 1 but was " + batchSize);
            }
            requirePositive(lease, "inventory.alerts.dispatch.lease");
            if (maxAttempts < 1) {
                throw new IllegalArgumentException(
                        "inventory.alerts.dispatch.max-attempts must be >= 1 but was " + maxAttempts);
            }
            requirePositive(baseDelay, "inventory.alerts.dispatch.base-delay");
            if (maxDelay == null || maxDelay.compareTo(baseDelay) < 0) {
                throw new IllegalArgumentException(
                        "inventory.alerts.dispatch.max-delay must be >= base-delay but was " + maxDelay);
            }
        }
    }

    public record Database(@DefaultValue("5s") Duration lockTimeout) {

        public Database {
            requirePositive(lockTimeout, "inventory.database.lock-timeout");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be positive but was " + value);
        }
    }
}

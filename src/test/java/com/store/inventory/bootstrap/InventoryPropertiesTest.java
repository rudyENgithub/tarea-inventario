package com.store.inventory.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class InventoryPropertiesTest {

    private static InventoryProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("inventory", InventoryProperties.class);
    }

    @Test
    void defaults() {
        InventoryProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("inventory", InventoryProperties.class);

        assertThat(properties.lowStock().threshold()).isEqualTo(5);
        assertThat(properties.alerts().relay().pollInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.alerts().dispatch().batchSize()).isEqualTo(50);
        assertThat(properties.alerts().dispatch().lease()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.alerts().dispatch().maxAttempts()).isEqualTo(5);
        assertThat(properties.alerts().dispatch().baseDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.alerts().dispatch().maxDelay()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.database().lockTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void overrides() {
        InventoryProperties properties = bind(Map.of(
                "inventory.low-stock.threshold", "10",
                "inventory.alerts.relay.poll-interval", "1s",
                "inventory.alerts.dispatch.batch-size", "7",
                "inventory.alerts.dispatch.lease", "PT30S",
                "inventory.alerts.dispatch.max-attempts", "3",
                "inventory.alerts.dispatch.base-delay", "10s",
                "inventory.alerts.dispatch.max-delay", "1h",
                "inventory.database.lock-timeout", "250ms"));

        assertThat(properties.lowStock().threshold()).isEqualTo(10);
        assertThat(properties.alerts().relay().pollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.alerts().dispatch().batchSize()).isEqualTo(7);
        assertThat(properties.alerts().dispatch().lease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.alerts().dispatch().maxAttempts()).isEqualTo(3);
        assertThat(properties.alerts().dispatch().baseDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.alerts().dispatch().maxDelay()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.database().lockTimeout()).isEqualTo(Duration.ofMillis(250));
    }

    private static void assertRejected(String property, String value, String expectedMessagePart) {
        assertThatThrownBy(() -> bind(Map.of(property, value)))
                .isInstanceOf(BindException.class)
                .hasStackTraceContaining(expectedMessagePart);
    }

    @Test
    void invalidThresholdIsRejectedNamingTheProperty() {
        assertRejected("inventory.low-stock.threshold", "-1", "inventory.low-stock.threshold");
    }

    @Test
    void invalidMaxAttemptsIsRejected() {
        assertRejected("inventory.alerts.dispatch.max-attempts", "0", "inventory.alerts.dispatch.max-attempts");
    }

    @Test
    void invalidLeaseIsRejected() {
        assertRejected("inventory.alerts.dispatch.lease", "PT0S", "inventory.alerts.dispatch.lease");
    }

    @Test
    void maxDelayBelowBaseDelayIsRejected() {
        assertRejected("inventory.alerts.dispatch.max-delay", "1s", "inventory.alerts.dispatch.max-delay");
    }
}

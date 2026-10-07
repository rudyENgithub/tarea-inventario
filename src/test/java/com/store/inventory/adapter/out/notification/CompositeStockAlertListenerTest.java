package com.store.inventory.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.application.port.out.AlertChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompositeStockAlertListenerTest {

    private final List<String> calls = new ArrayList<>();

    private AlertChannel recording(String name) {
        return AlertChannel.of(name, (sku, available) -> calls.add(name + ":" + sku + ":" + available));
    }

    private AlertChannel failing(String name, RuntimeException failure) {
        return AlertChannel.of(name, (sku, available) -> {
            calls.add(name + ":" + sku + ":" + available);
            throw failure;
        });
    }

    @Test
    @DisplayName("RNF-3: every channel receives the alert, in registration order")
    void invokesAllChannelsInOrder() {
        var composite = new CompositeStockAlertListener(List.of(recording("a"), recording("b"), recording("c")));

        composite.onLowStock("SKU", 4);

        assertThat(calls).containsExactly("a:SKU:4", "b:SKU:4", "c:SKU:4");
    }

    @Test
    @DisplayName("RF-A4: a failing channel does not stop the others and is reported once")
    void failingChannelDoesNotBlockTheRest() {
        var boom = new IllegalStateException("smtp down");
        var composite = new CompositeStockAlertListener(List.of(recording("a"), failing("b", boom), recording("c")));

        assertThatThrownBy(() -> composite.onLowStock("SKU", 4))
                .isInstanceOfSatisfying(AlertDeliveryException.class, e -> {
                    assertThat(e.failuresByChannel()).containsOnlyKeys("b");
                    assertThat(e.failuresByChannel().get("b")).isSameAs(boom);
                    assertThat(e.getSuppressed()).containsExactly(boom);
                    assertThat(e).hasMessage("Delivery failed for channels: [b]");
                });
        assertThat(calls).containsExactly("a:SKU:4", "b:SKU:4", "c:SKU:4");
    }

    @Test
    void reportsEveryFailingChannelInOrder() {
        var composite = new CompositeStockAlertListener(List.of(
                failing("x", new IllegalStateException("1")), recording("ok"), failing("y", new RuntimeException("2"))));

        assertThatThrownBy(() -> composite.onLowStock("SKU", 1))
                .isInstanceOfSatisfying(AlertDeliveryException.class, e -> {
                    assertThat(e.failuresByChannel().keySet()).containsExactly("x", "y");
                    assertThat(e).hasMessage("Delivery failed for channels: [x, y]");
                });
    }

    @Test
    void emptyCompositeDoesNothing() {
        new CompositeStockAlertListener(List.of()).onLowStock("SKU", 1);

        assertThat(calls).isEmpty();
    }

    @Test
    void rejectsNullAndDuplicateChannels() {
        assertThatThrownBy(() -> new CompositeStockAlertListener(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompositeStockAlertListener(Arrays.asList(recording("a"), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompositeStockAlertListener(List.of(recording("a"), recording("a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate channel name: a");
    }

    @Test
    void errorsPropagateImmediately() {
        var composite = new CompositeStockAlertListener(List.of(
                failing("a", new RuntimeException("x")), AlertChannel.of("b", (sku, available) -> {
                    throw new AssertionError("fatal");
                }), recording("c")));

        assertThatThrownBy(() -> composite.onLowStock("SKU", 1))
                .isInstanceOf(AssertionError.class)
                .hasMessage("fatal");
        assertThat(calls).containsExactly("a:SKU:1");
    }

    @Test
    void takesADefensiveCopyOfTheChannels() {
        List<AlertChannel> channels = new ArrayList<>(List.of(recording("a")));
        var composite = new CompositeStockAlertListener(channels);
        channels.add(recording("b"));

        composite.onLowStock("SKU", 1);

        assertThat(calls).containsExactly("a:SKU:1");
    }
}

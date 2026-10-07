package com.store.inventory.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.application.port.out.AlertChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SafeStockAlertListenerTest {

    private record Failure(String channel, String sku, int available, Throwable cause) {
    }

    private final List<Failure> failures = new ArrayList<>();

    private SafeStockAlertListener safe(AlertChannel delegate) {
        return new SafeStockAlertListener(delegate,
                (channel, sku, available, cause) -> failures.add(new Failure(channel, sku, available, cause)));
    }

    @Test
    void delegatesOnSuccess() {
        List<String> received = new ArrayList<>();
        var listener = safe(AlertChannel.of("x", (sku, available) -> received.add(sku + available)));

        listener.onLowStock("A", 3);

        assertThat(received).containsExactly("A3");
        assertThat(failures).isEmpty();
        assertThat(listener.name()).isEqualTo("x");
    }

    @Test
    @DisplayName("RF-A4: a failing channel is contained and reported once to the observer")
    void containsRuntimeExceptions() {
        var boom = new IllegalStateException("boom");
        var listener = safe(AlertChannel.of("mail", (sku, available) -> {
            throw boom;
        }));

        assertThatCode(() -> listener.onLowStock("A", 3)).doesNotThrowAnyException();

        assertThat(failures).containsExactly(new Failure("mail", "A", 3, boom));
    }

    @Test
    void aFailingObserverDoesNotBreakTheListener() {
        var listener = new SafeStockAlertListener(AlertChannel.of("mail", (sku, available) -> {
            throw new RuntimeException("channel");
        }), (channel, sku, available, cause) -> {
            throw new RuntimeException("observer");
        });

        assertThatCode(() -> listener.onLowStock("A", 3)).doesNotThrowAnyException();
    }

    @Test
    void errorsPropagate() {
        var listener = safe(AlertChannel.of("mail", (sku, available) -> {
            throw new OutOfMemoryError("fatal");
        }));

        assertThatThrownBy(() -> listener.onLowStock("A", 3)).isInstanceOf(OutOfMemoryError.class);
        assertThat(failures).isEmpty();
    }

    @Test
    void doesNotClearTheInterruptFlag() {
        var listener = safe(AlertChannel.of("mail", (sku, available) -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }));

        try {
            listener.onLowStock("A", 3);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    static Stream<RuntimeException> hundredDifferentFailures() {
        List<RuntimeException> all = new ArrayList<>(List.of(
                new RuntimeException("r"), new IllegalStateException("s"), new IllegalArgumentException("a"),
                new UnsupportedOperationException("u"), new NullPointerException("n"),
                new ArithmeticException("m"), new ClassCastException("c"), new IndexOutOfBoundsException("i"),
                new java.util.ConcurrentModificationException("cm"), new java.io.UncheckedIOException(
                        new java.io.IOException("io"))));
        for (int i = all.size(); i < 100; i++) {
            all.add(new RuntimeException("generic-" + i, new Exception("cause-" + i)));
        }
        return all.stream();
    }

    @ParameterizedTest
    @MethodSource("hundredDifferentFailures")
    @DisplayName("RF-A4: no RuntimeException ever escapes")
    void neverThrowsRuntimeException(RuntimeException failure) {
        var listener = safe(AlertChannel.of("mail", (sku, available) -> {
            throw failure;
        }));

        assertThatCode(() -> listener.onLowStock("A", 1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Safe(Composite) never propagates even if every channel fails")
    void safeCompositeNeverPropagates() {
        var composite = new CompositeStockAlertListener(List.of(
                AlertChannel.of("a", (sku, available) -> {
                    throw new RuntimeException("a");
                }),
                AlertChannel.of("b", (sku, available) -> {
                    throw new RuntimeException("b");
                })));
        var safe = new SafeStockAlertListener(AlertChannel.of("composite", composite));

        assertThatCode(() -> safe.onLowStock("A", 1)).doesNotThrowAnyException();
    }
}

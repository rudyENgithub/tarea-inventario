package com.store.inventory.application.alert;

import static com.store.inventory.testsupport.ConcurrentTestSupport.awaitCondition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.adapter.out.persistence.memory.InMemoryAlertOutbox;
import com.store.inventory.adapter.out.persistence.memory.InMemoryAlertOutbox.Status;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.domain.alert.LowStockAlert;
import com.store.inventory.testsupport.MutableClock;
import com.store.inventory.testsupport.RecordingAlertListener;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class AlertRelayTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration HOUR = Duration.ofHours(1);

    private final InMemoryAlertOutbox outbox = new InMemoryAlertOutbox();
    private final MutableClock clock = new MutableClock(T0);
    private AlertRelay relay;

    @AfterEach
    void closeRelay() {
        if (relay != null) {
            relay.close();
        }
    }

    private AlertDispatcher dispatcher(StockAlertListener listener, AlertRetryPolicy policy) {
        return new AlertDispatcher(outbox, listener, clock, policy, 50, Duration.ofMinutes(1));
    }

    private AlertRelay start(IntSupplier pass, Duration pollInterval) {
        relay = new AlertRelay(pass, pollInterval);
        relay.start();
        return relay;
    }

    private void enqueue(String sku) {
        outbox.enqueue(new LowStockAlert(sku, 4, T0));
    }

    @RepeatedTest(20)
    @DisplayName("RNF-5: a signal wakes the relay immediately, even with a one hour poll interval")
    void signalWakesTheRelay() {
        var listener = new RecordingAlertListener();
        start(dispatcher(listener, AlertRetryPolicy.noRetry())::dispatchPending, HOUR);

        enqueue("A");
        relay.signal();

        assertThat(listener.await(1, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    @DisplayName("without a signal the polling picks the alert up")
    void pollingDelivers() {
        var listener = new RecordingAlertListener();
        start(dispatcher(listener, AlertRetryPolicy.noRetry())::dispatchPending, Duration.ofMillis(20));

        enqueue("A");

        assertThat(listener.await(1, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    @DisplayName("1000 signals coalesce: every alert is delivered once and passes never exceed the signals")
    void signalsCoalesce() {
        var listener = new RecordingAlertListener();
        var dispatcher = dispatcher(listener, AlertRetryPolicy.noRetry());
        AtomicInteger passes = new AtomicInteger();
        start(() -> {
            passes.incrementAndGet();
            return dispatcher.dispatchPending();
        }, HOUR);
        awaitCondition(() -> passes.get() >= 1, Duration.ofSeconds(5));
        int startupPasses = passes.get();

        for (int i = 0; i < 1_000; i++) {
            enqueue("SKU-" + i);
            relay.signal();
        }

        assertThat(listener.await(1_000, Duration.ofSeconds(10))).isTrue();
        assertThat(listener.count()).isEqualTo(1_000);
        assertThat(new HashSet<>(listener.alerts().stream().map(RecordingAlertListener.Alert::sku).toList()))
                .hasSize(1_000);
        assertThat(passes.get() - startupPasses).isLessThanOrEqualTo(1_000);
    }

    @Test
    @DisplayName("RF-A4: a pass that throws does not kill the relay")
    void survivesFailingPasses() {
        var listener = new RecordingAlertListener();
        var dispatcher = dispatcher(listener, AlertRetryPolicy.noRetry());
        AtomicInteger calls = new AtomicInteger();
        start(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("database down");
            }
            return dispatcher.dispatchPending();
        }, HOUR);
        awaitCondition(() -> calls.get() >= 1, Duration.ofSeconds(5));

        enqueue("A");
        relay.signal();

        assertThat(listener.await(1, Duration.ofSeconds(5))).isTrue();
        assertThat(relay.isAlive()).isTrue();
    }

    @RepeatedTest(20)
    @DisplayName("close is idempotent, leaves no live thread and is safe to signal afterwards")
    void closeIsIdempotent() {
        start(() -> 0, HOUR);
        assertThat(relay.isAlive()).isTrue();

        relay.close();
        relay.close();

        assertThat(relay.isAlive()).isFalse();
        assertThatCode(relay::signal).doesNotThrowAnyException();
        assertThatThrownBy(relay::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannotStartTwice() {
        start(() -> 0, HOUR);

        assertThatThrownBy(relay::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void signalBeforeStartIsHarmless() {
        relay = new AlertRelay(() -> 0, HOUR);

        assertThatCode(relay::signal).doesNotThrowAnyException();
        assertThatCode(relay::close).doesNotThrowAnyException();
        assertThat(relay.isAlive()).isFalse();
    }

    @Test
    @DisplayName("close waits for the delivery in progress and does not lose the alert")
    void closeWaitsForTheCurrentDelivery() throws Exception {
        CountDownLatch delivering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StockAlertListener slow = (sku, available) -> {
            delivering.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        start(dispatcher(slow, AlertRetryPolicy.noRetry())::dispatchPending, HOUR);
        enqueue("A");
        relay.signal();
        assertThat(delivering.await(5, TimeUnit.SECONDS)).isTrue();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> closing = executor.submit(relay::close);
            assertThatThrownBy(() -> closing.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            release.countDown();
            closing.get(10, TimeUnit.SECONDS);
        }

        assertThat(outbox.entries().get(0).status()).isEqualTo(Status.DELIVERED);
        assertThat(relay.isAlive()).isFalse();
    }

    @Test
    @DisplayName("the relay runs on a virtual thread named alert-relay")
    void runsOnANamedVirtualThread() {
        AtomicReference<Thread> deliveredOn = new AtomicReference<>();
        var listener = new RecordingAlertListener();
        StockAlertListener capturing = (sku, available) -> {
            deliveredOn.set(Thread.currentThread());
            listener.onLowStock(sku, available);
        };
        start(dispatcher(capturing, AlertRetryPolicy.noRetry())::dispatchPending, HOUR);

        enqueue("A");
        relay.signal();

        assertThat(listener.await(1)).isTrue();
        assertThat(deliveredOn.get().isVirtual()).isTrue();
        assertThat(deliveredOn.get().getName()).isEqualTo("alert-relay");
    }

    @Test
    @DisplayName("RNF-5: failed deliveries are retried after the backoff once the relay is woken again")
    void retriesAfterTheBackoff() {
        var listener = RecordingAlertListener.failing(new IllegalStateException("smtp down"));
        var policy = new AlertRetryPolicy(3, Duration.ofSeconds(10), HOUR);
        start(dispatcher(listener, policy)::dispatchPending, HOUR);
        enqueue("A");
        relay.signal();
        awaitCondition(() -> listener.count() == 1 && outbox.entries().get(0).status() == Status.PENDING,
                Duration.ofSeconds(5));

        clock.advance(Duration.ofSeconds(10));
        relay.signal();

        awaitCondition(() -> listener.count() == 2, Duration.ofSeconds(5));
        awaitCondition(() -> outbox.entries().get(0).status() == Status.PENDING
                && outbox.entries().get(0).attempts() == 2, Duration.ofSeconds(5));
    }
}

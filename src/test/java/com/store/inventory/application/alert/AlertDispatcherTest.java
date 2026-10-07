package com.store.inventory.application.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.adapter.out.notification.CompositeStockAlertListener;
import com.store.inventory.adapter.out.notification.SafeStockAlertListener;
import com.store.inventory.adapter.out.persistence.memory.InMemoryAlertOutbox;
import com.store.inventory.adapter.out.persistence.memory.InMemoryAlertOutbox.Status;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.application.port.out.AlertChannel;
import com.store.inventory.application.port.out.PendingAlert;
import com.store.inventory.domain.alert.LowStockAlert;
import com.store.inventory.testsupport.MutableClock;
import com.store.inventory.testsupport.RecordingAlertListener;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class AlertDispatcherTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(1);

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryAlertOutbox outbox = new InMemoryAlertOutbox();

    private AlertDispatcher dispatcher(StockAlertListener listener, AlertRetryPolicy policy, int batchSize) {
        return new AlertDispatcher(outbox, listener, clock, policy, batchSize, LEASE);
    }

    private long enqueue(String sku, int available) {
        return outbox.enqueue(new LowStockAlert(sku, available, T0));
    }

    private Status statusOf(long id) {
        return outbox.entries().stream().filter(e -> e.id() == id).findFirst().orElseThrow().status();
    }

    private int attemptsOf(long id) {
        return outbox.entries().stream().filter(e -> e.id() == id).findFirst().orElseThrow().attempts();
    }

    private static StockAlertListener failing() {
        return (sku, available) -> {
            throw new IllegalStateException("channel down");
        };
    }

    @Test
    @DisplayName("RF-A1: a successful delivery marks DELIVERED and notifies exactly once")
    void successfulDelivery() {
        var listener = new RecordingAlertListener();
        long id = enqueue("A", 4);

        int delivered = dispatcher(listener, AlertRetryPolicy.noRetry(), 10).dispatchPending();
        dispatcher(listener, AlertRetryPolicy.noRetry(), 10).dispatchPending();

        assertThat(delivered).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo(Status.DELIVERED);
        assertThat(listener.alerts()).containsExactly(new RecordingAlertListener.Alert("A", 4));
    }

    @Test
    @DisplayName("RF-A4: a failing listener with no retries ends FAILED and never breaks dispatchPending")
    void failureWithoutRetry() {
        long id = enqueue("A", 4);
        var dispatcher = dispatcher(failing(), AlertRetryPolicy.noRetry(), 10);

        assertThatCode(dispatcher::dispatchPending).doesNotThrowAnyException();
        clock.advance(Duration.ofDays(1));
        assertThat(dispatcher.dispatchPending()).isZero();

        assertThat(statusOf(id)).isEqualTo(Status.FAILED);
        assertThat(attemptsOf(id)).isEqualTo(1);
        assertThat(outbox.entries().get(0).lastError()).contains("channel down");
    }

    @Test
    @DisplayName("RNF-5: retries with backoff and becomes FAILED after the last attempt")
    void retriesWithBackoffThenFails() {
        long id = enqueue("A", 4);
        var dispatcher = dispatcher(failing(),
                new AlertRetryPolicy(3, Duration.ofSeconds(10), Duration.ofHours(1)), 10);

        dispatcher.dispatchPending();
        assertThat(statusOf(id)).isEqualTo(Status.PENDING);

        clock.advance(Duration.ofSeconds(9));
        dispatcher.dispatchPending();
        assertThat(attemptsOf(id)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        dispatcher.dispatchPending();
        assertThat(attemptsOf(id)).isEqualTo(2);
        assertThat(statusOf(id)).isEqualTo(Status.PENDING);

        clock.advance(Duration.ofSeconds(19));
        dispatcher.dispatchPending();
        assertThat(attemptsOf(id)).isEqualTo(2);

        clock.advance(Duration.ofSeconds(1));
        dispatcher.dispatchPending();
        assertThat(attemptsOf(id)).isEqualTo(3);
        assertThat(statusOf(id)).isEqualTo(Status.FAILED);
    }

    @Test
    void succeedsOnTheSecondAttempt() {
        long id = enqueue("A", 4);
        AtomicInteger calls = new AtomicInteger();
        var listener = (StockAlertListener) (sku, available) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("first time fails");
            }
        };
        var dispatcher = dispatcher(listener, new AlertRetryPolicy(3, Duration.ofSeconds(10), Duration.ofHours(1)), 10);

        dispatcher.dispatchPending();
        clock.advance(Duration.ofSeconds(10));
        int delivered = dispatcher.dispatchPending();

        assertThat(delivered).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo(Status.DELIVERED);
        assertThat(attemptsOf(id)).isEqualTo(2);
    }

    @Test
    void oneFailureDoesNotBlockTheNextEntryOfTheSameBatch() {
        long first = enqueue("BAD", 1);
        long second = enqueue("GOOD", 2);
        var listener = new RecordingAlertListener();
        StockAlertListener selective = (sku, available) -> {
            if (sku.equals("BAD")) {
                throw new IllegalStateException("bad sku");
            }
            listener.onLowStock(sku, available);
        };

        int delivered = dispatcher(selective, AlertRetryPolicy.noRetry(), 10).dispatchPending();

        assertThat(delivered).isEqualTo(1);
        assertThat(statusOf(first)).isEqualTo(Status.FAILED);
        assertThat(statusOf(second)).isEqualTo(Status.DELIVERED);
    }

    @Test
    void drainsMoreEntriesThanTheBatchSizeInOneCall() {
        for (int i = 0; i < 10; i++) {
            enqueue("SKU-" + i, 1);
        }
        var listener = new RecordingAlertListener();

        int delivered = dispatcher(listener, AlertRetryPolicy.noRetry(), 3).dispatchPending();

        assertThat(delivered).isEqualTo(10);
        assertThat(listener.count()).isEqualTo(10);
    }

    @Test
    @DisplayName("an entry claimed but never resolved is redelivered once its lease expires")
    void expiredLeaseIsRedelivered() {
        long id = enqueue("A", 4);
        List<PendingAlert> claimedByDeadConsumer = outbox.claimDue(clock.instant(), 1, LEASE);
        assertThat(claimedByDeadConsumer).hasSize(1);
        var listener = new RecordingAlertListener();
        var dispatcher = dispatcher(listener, AlertRetryPolicy.noRetry(), 10);

        assertThat(dispatcher.dispatchPending()).isZero();
        clock.advance(LEASE);
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);

        assertThat(statusOf(id)).isEqualTo(Status.DELIVERED);
        assertThat(attemptsOf(id)).isEqualTo(2);
    }

    @RepeatedTest(20)
    @DisplayName("RNF-5: 8 concurrent dispatchers deliver each of 100 alerts exactly once")
    void concurrentDispatchersDeliverOnce() throws Exception {
        var listener = new RecordingAlertListener();
        var dispatcher = dispatcher(listener, AlertRetryPolicy.noRetry(), 7);
        for (int i = 0; i < 100; i++) {
            enqueue("SKU-" + i, 1);
        }
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return dispatcher.dispatchPending();
                }));
            }
            int total = 0;
            for (Future<Integer> future : futures) {
                total += future.get(10, TimeUnit.SECONDS);
            }
            assertThat(total).isEqualTo(100);
        }

        assertThat(listener.count()).isEqualTo(100);
        var skus = listener.alerts().stream().map(RecordingAlertListener.Alert::sku).toList();
        assertThat(new HashSet<>(skus)).hasSize(100);
    }

    @Test
    void anErrorPropagatesAndLeavesTheEntryClaimableAfterTheLease() {
        long id = enqueue("A", 4);
        StockAlertListener fatal = (sku, available) -> {
            throw new AssertionError("fatal");
        };

        assertThatThrownBy(() -> dispatcher(fatal, AlertRetryPolicy.noRetry(), 10).dispatchPending())
                .isInstanceOf(AssertionError.class);

        assertThat(statusOf(id)).isEqualTo(Status.IN_PROGRESS);
        clock.advance(LEASE);
        var listener = new RecordingAlertListener();
        assertThat(dispatcher(listener, AlertRetryPolicy.noRetry(), 10).dispatchPending()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo(Status.DELIVERED);
    }

    @Test
    @DisplayName("Safe(Composite[failing, good]) is delivered and the good channel is notified")
    void safeCompositeWithAFailingChannel() {
        long id = enqueue("A", 4);
        var good = new RecordingAlertListener();
        var composite = new CompositeStockAlertListener(List.of(
                AlertChannel.of("bad", failing()), AlertChannel.of("good", good)));
        var safe = new SafeStockAlertListener(AlertChannel.of("composite", composite));

        dispatcher(safe, AlertRetryPolicy.noRetry(), 10).dispatchPending();

        assertThat(statusOf(id)).isEqualTo(Status.DELIVERED);
        assertThat(good.alerts()).containsExactly(new RecordingAlertListener.Alert("A", 4));
    }

    @Test
    void validatesConstructorArguments() {
        var listener = new RecordingAlertListener();
        assertThatThrownBy(() -> new AlertDispatcher(outbox, listener, clock, AlertRetryPolicy.noRetry(), 0, LEASE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertDispatcher(outbox, listener, clock, AlertRetryPolicy.noRetry(), 1,
                Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}

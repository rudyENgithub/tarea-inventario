package com.store.inventory.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.domain.alert.LowStockAlert;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Contrato de comportamiento que todo adaptador de {@link AlertOutbox} debe cumplir. */
public abstract class AlertOutboxContractTest {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    protected abstract AlertOutbox outbox();

    /** Inserta una alerta a través del adaptador concreto y devuelve su id. */
    protected abstract long enqueue(LowStockAlert alert);

    /** Uno de PENDING, IN_PROGRESS, DELIVERED o FAILED. */
    protected abstract String statusOf(long id);

    private long enqueueAlert(String sku) {
        return enqueue(new LowStockAlert(sku, 4, T0));
    }

    private static List<Long> ids(List<PendingAlert> claimed) {
        return claimed.stream().map(PendingAlert::id).toList();
    }

    @Test
    @DisplayName("claiming from an empty outbox returns nothing")
    void emptyClaim() {
        assertThat(outbox().claimDue(T0, 10, LEASE)).isEmpty();
    }

    @Test
    @DisplayName("entries are claimed FIFO by id and the limit is honoured")
    void fifoAndLimit() {
        long first = enqueueAlert("A");
        long second = enqueueAlert("B");
        long third = enqueueAlert("C");

        assertThat(ids(outbox().claimDue(T0, 2, LEASE))).containsExactly(first, second);
        assertThat(ids(outbox().claimDue(T0, 2, LEASE))).containsExactly(third);
    }

    @Test
    @DisplayName("the first claim reports attempt 1 and carries the alert")
    void firstAttemptIsOne() {
        enqueueAlert("A");

        List<PendingAlert> claimed = outbox().claimDue(T0, 1, LEASE);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).attempts()).isEqualTo(1);
        assertThat(claimed.get(0).alert()).isEqualTo(new LowStockAlert("A", 4, T0));
    }

    @Test
    @DisplayName("a claimed entry is reclaimable exactly when its lease ends, with attempts + 1")
    void leaseExpiry() {
        long id = enqueueAlert("A");
        outbox().claimDue(T0, 1, LEASE);
        assertThat(statusOf(id)).isEqualTo("IN_PROGRESS");

        assertThat(outbox().claimDue(T0.plus(LEASE).minusNanos(1_000), 1, LEASE)).isEmpty();

        List<PendingAlert> again = outbox().claimDue(T0.plus(LEASE), 1, LEASE);
        assertThat(ids(again)).containsExactly(id);
        assertThat(again.get(0).attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a delivered entry never reappears and delivering again is a no-op")
    void delivered() {
        long id = enqueueAlert("A");
        outbox().claimDue(T0, 1, LEASE);

        outbox().markDelivered(id, T0);
        outbox().markDelivered(id, T0);

        assertThat(statusOf(id)).isEqualTo("DELIVERED");
        assertThat(outbox().claimDue(T0.plus(Duration.ofDays(30)), 10, LEASE)).isEmpty();
    }

    @Test
    @DisplayName("a retried entry is hidden until its next attempt time (inclusive) and then has attempts + 1")
    void retry() {
        long id = enqueueAlert("A");
        outbox().claimDue(T0, 1, LEASE);
        Instant retryAt = T0.plusSeconds(60);

        outbox().markRetry(id, retryAt, "smtp down");

        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(outbox().claimDue(retryAt.minusNanos(1_000), 1, LEASE)).isEmpty();
        List<PendingAlert> again = outbox().claimDue(retryAt, 1, LEASE);
        assertThat(ids(again)).containsExactly(id);
        assertThat(again.get(0).attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a failed entry is terminal and further transitions are no-ops")
    void failed() {
        long id = enqueueAlert("A");
        outbox().claimDue(T0, 1, LEASE);

        outbox().markFailed(id, "gave up");
        outbox().markDelivered(id, T0);
        outbox().markRetry(id, T0, "late");

        assertThat(statusOf(id)).isEqualTo("FAILED");
        assertThat(outbox().claimDue(T0.plus(Duration.ofDays(30)), 10, LEASE)).isEmpty();
    }

    @Test
    @DisplayName("an entry whose next attempt is in the future is not claimed")
    void futureEntryIsNotDue() {
        long id = enqueue(new LowStockAlert("A", 4, T0.plusSeconds(10)));

        assertThat(outbox().claimDue(T0, 10, LEASE)).isEmpty();
        assertThat(ids(outbox().claimDue(T0.plusSeconds(10), 10, LEASE))).containsExactly(id);
    }

    @Test
    @DisplayName("invalid limit or lease is rejected")
    void validations() {
        assertThatThrownBy(() -> outbox().claimDue(T0, 0, LEASE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox().claimDue(T0, 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox().claimDue(T0, 1, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @RepeatedTest(20)
    @DisplayName("RNF-5: concurrent consumers never receive the same entry and none is lost")
    void concurrentClaimsAreDisjointAndComplete() throws Exception {
        int entries = 100;
        int consumers = 8;
        Set<Long> expected = new HashSet<>();
        for (int i = 0; i < entries; i++) {
            expected.add(enqueueAlert("SKU-" + i));
        }
        CyclicBarrier start = new CyclicBarrier(consumers);

        List<Long> received = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int i = 0; i < consumers; i++) {
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    List<Long> mine = new ArrayList<>();
                    List<PendingAlert> batch;
                    while (!(batch = outbox().claimDue(T0, 10, LEASE)).isEmpty()) {
                        mine.addAll(ids(batch));
                    }
                    return mine;
                }));
            }
            for (Future<List<Long>> future : futures) {
                received.addAll(future.get(10, TimeUnit.SECONDS));
            }
        }

        assertThat(received).hasSize(entries).doesNotHaveDuplicates();
        assertThat(new HashSet<>(received)).isEqualTo(expected);
    }
}

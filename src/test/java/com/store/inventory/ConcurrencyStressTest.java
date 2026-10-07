package com.store.inventory;

import static com.store.inventory.testsupport.ConcurrentTestSupport.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

import com.store.inventory.adapter.out.persistence.memory.InMemoryAlertOutbox;
import com.store.inventory.adapter.out.persistence.memory.InMemoryInventoryRepository;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.application.DefaultInventoryService;
import com.store.inventory.application.alert.AlertDispatchTrigger;
import com.store.inventory.application.port.out.OperationResult;
import com.store.inventory.domain.alert.LowStockDetector;
import com.store.inventory.domain.alert.LowStockThreshold;
import com.store.inventory.domain.policy.CategoryPolicyRegistry;
import com.store.inventory.domain.policy.DefaultCategoryPolicies;
import com.store.inventory.testsupport.ConcurrentTestSupport.Outcome;
import com.store.inventory.testsupport.MutableClock;
import com.store.inventory.testsupport.RecordingAlertListener;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pruebas de estrés del servicio en memoria bajo alta contención (hilos virtuales liberados por una barrera de inicio).
 * Ejecútalas solas con {@code mvn -q test -Dgroups=concurrency}.
 */
@Tag("concurrency")
class ConcurrencyStressTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long SEED = 20260101L;

    private final MutableClock clock = new MutableClock(T0);
    private final RecordingAlertListener listener = new RecordingAlertListener();
    private final InventoryService service = Inventory.create(clock, listener);

    private void product(String sku, int stock) {
        service.registerProduct(sku, ProductCategory.STANDARD);
        service.addStock(sku, stock);
    }

    private static <T> List<T> successes(List<Outcome<T>> outcomes) {
        return outcomes.stream().filter(Outcome::succeeded).map(Outcome::value).toList();
    }

    private static void assertOnlyInsufficientStock(List<? extends Outcome<?>> outcomes) {
        outcomes.stream().filter(o -> !o.succeeded()).forEach(o -> assertThat(o.failure())
                .as("unexpected failure %s", o.failure()).isInstanceOf(InsufficientStockException.class));
    }

    @RepeatedTest(20)
    @DisplayName("RNF-1: 500 tasks reserving 1 unit over 50 units sell exactly 50")
    void noOverselling() {
        product("A", 50);

        List<Outcome<Reservation>> outcomes = runConcurrently(500, i -> () -> service.reserve("O" + i, "A", 1));

        List<Reservation> granted = successes(outcomes);
        assertThat(granted).hasSize(50);
        assertThat(outcomes.stream().filter(o -> !o.succeeded())).hasSize(450);
        assertOnlyInsufficientStock(outcomes);
        assertThat(service.available("A")).isZero();
        assertThat(granted.stream().mapToInt(Reservation::quantity).sum()).isEqualTo(50);
    }

    @RepeatedTest(20)
    @DisplayName("RNF-1: mixed quantities never grant more than the stock")
    void mixedQuantities() {
        product("A", 100);
        Random random = new Random(SEED);
        int[] quantities = random.ints(300, 1, 6).toArray();

        List<Outcome<Reservation>> outcomes =
                runConcurrently(quantities.length, i -> () -> service.reserve("O" + i, "A", quantities[i]));

        int granted = successes(outcomes).stream().mapToInt(Reservation::quantity).sum();
        assertOnlyInsufficientStock(outcomes);
        assertThat(granted).isLessThanOrEqualTo(100);
        assertThat(service.available("A")).isEqualTo(100 - granted).isGreaterThanOrEqualTo(0);
    }

    @RepeatedTest(20)
    @DisplayName("AD-3: 200 concurrent retries of the same order reserve only once")
    void concurrentIdempotency() {
        product("A", 10);

        List<Outcome<Reservation>> outcomes = runConcurrently(200, i -> () -> service.reserve("SAME", "A", 3));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(successes(outcomes).stream().distinct()).hasSize(1);
        assertThat(service.available("A")).isEqualTo(7);
    }

    @RepeatedTest(20)
    @DisplayName("AD-3: 100 concurrent confirms discount only once")
    void concurrentConfirm() {
        product("A", 5);
        service.reserve("O1", "A", 2);

        List<Outcome<Boolean>> outcomes = runConcurrently(100, i -> () -> {
            service.confirm("O1");
            return Boolean.TRUE;
        });

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(service.available("A")).isEqualTo(3);
    }

    @RepeatedTest(20)
    @DisplayName("RF-A2: a single alert even if 200 tasks race across the threshold")
    void singleAlert() {
        product("A", 100);

        List<Outcome<Reservation>> outcomes = runConcurrently(200, i -> () -> service.reserve("O" + i, "A", 1));

        assertThat(successes(outcomes)).hasSize(100);
        assertOnlyInsufficientStock(outcomes);
        assertThat(service.available("A")).isZero();
        assertThat(listener.alerts()).containsExactly(new RecordingAlertListener.Alert("A", 5));
    }

    @Test
    @DisplayName("RNF-6: a busy SKU does not delay other SKUs")
    void independentSkus() throws Exception {
        var outbox = new InMemoryAlertOutbox();
        var repository = new InMemoryInventoryRepository(outbox);
        AlertDispatchTrigger noTrigger = () -> { };
        var looseService = new DefaultInventoryService(repository,
                CategoryPolicyRegistry.of(DefaultCategoryPolicies.all()),
                new LowStockDetector(LowStockThreshold.DEFAULT), noTrigger, clock);
        looseService.registerProduct("A", ProductCategory.STANDARD);
        looseService.addStock("A", 10);
        looseService.registerProduct("B", ProductCategory.STANDARD);
        looseService.addStock("B", 1_000);
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> blockedA = executor.submit(() -> repository.executeOnProduct("A", product -> {
                aEntered.countDown();
                awaitUninterruptibly(release);
                return OperationResult.of(Boolean.TRUE);
            }));
            assertThat(aEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Integer> reservingB = executor.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    looseService.reserve("B" + i, "B", 1);
                }
                return looseService.available("B");
            });
            try {
                assertThat(reservingB.get(2, TimeUnit.SECONDS)).isEqualTo(900);
            } finally {
                release.countDown();
            }
            blockedA.get(5, TimeUnit.SECONDS);
        }
    }

    @RepeatedTest(20)
    @DisplayName("RNF-6: 2000 tasks over 20 SKUs never oversell any of them")
    void multiSkuThroughput() {
        int skus = 20;
        int stockPerSku = 25;
        for (int s = 0; s < skus; s++) {
            product("SKU-" + s, stockPerSku);
        }
        int[] target = new Random(SEED).ints(2_000, 0, skus).toArray();

        List<Outcome<Reservation>> outcomes =
                runConcurrently(target.length, i -> () -> service.reserve("O" + i, "SKU-" + target[i], 1));

        assertOnlyInsufficientStock(outcomes);
        int totalGranted = 0;
        for (int s = 0; s < skus; s++) {
            String sku = "SKU-" + s;
            long granted = successes(outcomes).stream().filter(r -> r.sku().equals(sku)).count();
            assertThat(granted).isLessThanOrEqualTo(stockPerSku);
            assertThat(service.available(sku)).isEqualTo(stockPerSku - (int) granted).isGreaterThanOrEqualTo(0);
            totalGranted += (int) granted;
        }
        assertThat(totalGranted).isEqualTo(successes(outcomes).size());
    }

    @RepeatedTest(20)
    @DisplayName("RF-E1/RNF-1: expired units are reused exactly once under concurrency")
    void expirationAndReservation() {
        product("A", 10);
        for (int i = 0; i < 10; i++) {
            service.reserve("OLD" + i, "A", 1);
        }
        assertThat(service.available("A")).isZero();
        clock.advance(Duration.ofMinutes(15));

        List<Outcome<Reservation>> outcomes = runConcurrently(100, i -> () -> service.reserve("NEW" + i, "A", 1));

        assertThat(successes(outcomes)).hasSize(10);
        assertOnlyInsufficientStock(outcomes);
        assertThat(service.available("A")).isZero();
    }

    @RepeatedTest(20)
    @DisplayName("RF-A3: the alert re-arms after a restock and fires exactly once more under load")
    void rearmUnderLoad() {
        product("A", 100);
        List<Outcome<Reservation>> first = runConcurrently(100, i -> () -> service.reserve("F" + i, "A", 1));
        assertThat(successes(first)).hasSize(100);
        assertThat(service.available("A")).isZero();
        assertThat(listener.count()).isEqualTo(1);

        service.addStock("A", 50);
        List<Outcome<Reservation>> second = runConcurrently(100, i -> () -> service.reserve("S" + i, "A", 1));

        assertThat(successes(second)).hasSize(50);
        assertOnlyInsufficientStock(second);
        assertThat(listener.alerts()).containsExactly(
                new RecordingAlertListener.Alert("A", 5), new RecordingAlertListener.Alert("A", 5));
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

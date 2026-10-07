package com.store.inventory.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.alert.LowStockAlert;
import com.store.inventory.domain.policy.CategoryPolicy;
import com.store.inventory.domain.policy.StandardCategoryPolicy;
import com.store.inventory.domain.stock.ProductStock;
import com.store.inventory.domain.stock.ReservationStatus;
import com.store.inventory.domain.stock.StockReservation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Contrato de comportamiento que todo adaptador de {@link InventoryRepository} debe cumplir. */
public abstract class InventoryRepositoryContractTest {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final CategoryPolicy STANDARD = new StandardCategoryPolicy();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    protected abstract InventoryRepository repository();

    /** Alertas registradas hasta ahora, sin consumirlas. */
    protected abstract List<LowStockAlert> enqueuedAlerts();

    private void stock(String sku, int units) {
        repository().registerIfAbsent(sku, ProductCategory.STANDARD);
        repository().executeOnProduct(sku, p -> {
            p.addStock(units);
            return OperationResult.of(units);
        }).orElseThrow();
    }

    private Optional<Boolean> reserve(String sku, String orderId, int quantity, Instant now) {
        return repository().executeOnProduct(sku, orderId, p -> {
            p.reserve(orderId, quantity, STANDARD, now);
            return OperationResult.of(Boolean.TRUE);
        });
    }

    // --- registro y lecturas básicas --------------------------------------------------------------------------

    @Test
    @DisplayName("registerIfAbsent returns the effective category and starts with zero stock")
    void registerIfAbsent() {
        assertThat(repository().registerIfAbsent("A", ProductCategory.FLASH_SALE)).isEqualTo(ProductCategory.FLASH_SALE);
        assertThat(repository().registerIfAbsent("A", ProductCategory.FLASH_SALE)).isEqualTo(ProductCategory.FLASH_SALE);
        assertThat(repository().registerIfAbsent("A", ProductCategory.STANDARD)).isEqualTo(ProductCategory.FLASH_SALE);
        assertThat(repository().availableUnits("A", T0)).isZero();
    }

    @Test
    @DisplayName("an unknown SKU has zero availability and its operation is never invoked")
    void unknownSku() {
        AtomicBoolean invoked = new AtomicBoolean();

        Optional<Integer> result = repository().executeOnProduct("NOPE", p -> {
            invoked.set(true);
            return OperationResult.of(1);
        });

        assertThat(repository().availableUnits("NOPE", T0)).isZero();
        assertThat(result).isEmpty();
        assertThat(invoked).isFalse();
    }

    @Test
    @DisplayName("state written by an operation is visible afterwards")
    void roundTrip() {
        repository().registerIfAbsent("A", ProductCategory.STANDARD);

        Optional<Integer> result = repository().executeOnProduct("A", p -> {
            p.addStock(10);
            return OperationResult.of(1);
        });

        assertThat(result).contains(1);
        assertThat(repository().availableUnits("A", T0)).isEqualTo(10);
    }

    // --- reservas -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a reservation round-trips with its expiry truncated to microseconds (D9)")
    void reservationRoundTrip() {
        stock("A", 10);
        Instant now = T0.plusNanos(123_456_789);

        reserve("A", "O1", 3, now);

        StockReservation stored = repository().findReservation("O1").orElseThrow();
        assertThat(stored.orderId()).isEqualTo("O1");
        assertThat(stored.sku()).isEqualTo("A");
        assertThat(stored.quantity()).isEqualTo(3);
        assertThat(stored.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(stored.expiresAt()).isEqualTo(T0.plusNanos(123_456_000).plus(Duration.ofMinutes(15)));
        assertThat(repository().availableUnits("A", now)).isEqualTo(7);
        assertThat(repository().availableUnits("A", stored.expiresAt())).isEqualTo(10);
    }

    @Test
    @DisplayName("confirming persists CONFIRMED and the reduced on-hand stock")
    void confirmPersists() {
        stock("A", 10);
        reserve("A", "O1", 4, T0);

        repository().executeOnProduct("A", "O1", p -> {
            p.confirm("O1", T0.plusSeconds(1));
            return OperationResult.of(1);
        }).orElseThrow();

        assertThat(repository().findReservation("O1").orElseThrow().status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(repository().availableUnits("A", T0.plusSeconds(1))).isEqualTo(6);
        assertThat(repository().availableUnits("A", T0.plus(Duration.ofDays(1)))).isEqualTo(6);
        int onHand = repository().executeOnProduct("A", p -> OperationResult.of(p.onHand())).orElseThrow();
        assertThat(onHand).isEqualTo(6);
    }

    @Test
    @DisplayName("the order of interest is loaded in any state, other confirmed ones are not")
    void orderOfInterestIsLoaded() {
        stock("A", 10);
        reserve("A", "O1", 2, T0);
        reserve("A", "O2", 2, T0);
        repository().executeOnProduct("A", "O1", p -> {
            p.confirm("O1", T0);
            return OperationResult.of(1);
        }).orElseThrow();

        boolean withInterest = repository().executeOnProduct("A", "O1",
                p -> OperationResult.of(p.reservation("O1").isPresent())).orElseThrow();
        boolean withoutInterest = repository().executeOnProduct("A",
                p -> OperationResult.of(p.reservation("O1").isPresent())).orElseThrow();
        boolean activeAlwaysLoaded = repository().executeOnProduct("A",
                p -> OperationResult.of(p.reservation("O2").isPresent())).orElseThrow();

        assertThat(withInterest).isTrue();
        assertThat(withoutInterest).isFalse();
        assertThat(activeAlwaysLoaded).isTrue();
    }

    @Test
    @DisplayName("purged reservations are deleted")
    void purgePersists() {
        stock("A", 10);
        reserve("A", "O1", 2, T0);
        Instant later = T0.plus(Duration.ofMinutes(20));

        int purged = repository().executeOnProduct("A", p -> OperationResult.of(p.purgeExpired(later))).orElseThrow();

        assertThat(purged).isEqualTo(1);
        assertThat(repository().findReservation("O1")).isEmpty();
        assertThat(repository().availableUnits("A", later)).isEqualTo(10);
    }

    // --- atomicidad ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RF-A4: an operation that throws persists nothing and propagates the same exception")
    void rollback() {
        stock("A", 10);
        reserve("A", "O1", 2, T0);
        List<LowStockAlert> alertsBefore = enqueuedAlerts();
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> repository().executeOnProduct("A", "O2", p -> {
            p.addStock(5);
            p.reserve("O2", 3, STANDARD, T0);
            p.confirm("O1", T0);
            throw boom;
        })).isSameAs(boom);

        assertThat(repository().availableUnits("A", T0)).isEqualTo(8);
        assertThat(repository().findReservation("O2")).isEmpty();
        assertThat(repository().findReservation("O1").orElseThrow().status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(enqueuedAlerts()).isEqualTo(alertsBefore);
    }

    @Test
    @DisplayName("alerts returned by an operation are recorded exactly once, together with the state")
    void alertsAreRecordedAtomically() {
        stock("A", 10);
        LowStockAlert alert = new LowStockAlert("A", 4, T0);

        repository().executeOnProduct("A", p -> {
            p.reserve("O1", 6, STANDARD, T0);
            return new OperationResult<>(1, List.of(alert));
        }).orElseThrow();

        assertThat(enqueuedAlerts()).containsExactly(alert);
        assertThatThrownBy(() -> repository().executeOnProduct("A", p -> {
            p.reserve("O2", 1, STANDARD, T0);
            throw new IllegalStateException("after building an alert");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(enqueuedAlerts()).containsExactly(alert);
    }

    // --- vinculación del id de orden ------------------------------------------------------------------------------

    @Test
    @DisplayName("D6: an order id bound to another SKU cannot be upserted, and the other SKU stays untouched")
    void orderIdConflict() {
        stock("A", 10);
        stock("B", 10);
        reserve("A", "O1", 2, T0);

        assertThatThrownBy(() -> reserve("B", "O1", 2, T0)).isInstanceOf(OrderIdConflictException.class);

        assertThat(repository().availableUnits("B", T0)).isEqualTo(10);
        assertThat(repository().availableUnits("A", T0)).isEqualTo(8);
        assertThat(repository().findReservation("O1").orElseThrow().sku()).isEqualTo("A");
    }

    @Test
    @DisplayName("an expired reservation of the same SKU and order id can be replaced")
    void replacingAnExpiredReservationIsAllowed() {
        stock("A", 10);
        reserve("A", "O1", 2, T0);
        Instant later = T0.plus(Duration.ofMinutes(20));

        reserve("A", "O1", 5, later);

        StockReservation stored = repository().findReservation("O1").orElseThrow();
        assertThat(stored.quantity()).isEqualTo(5);
        assertThat(stored.expiresAt()).isEqualTo(later.plus(Duration.ofMinutes(15)));
        assertThat(repository().availableUnits("A", later)).isEqualTo(5);
    }

    // --- concurrencia -----------------------------------------------------------------------------------------------

    @RepeatedTest(20)
    @DisplayName("RNF-2: two operations on the same SKU never overlap")
    void sameSkuIsMutuallyExclusive() throws Exception {
        stock("A", 10);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondEntered = new AtomicBoolean();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> repository().executeOnProduct("A", p -> {
                firstEntered.countDown();
                awaitQuietly(releaseFirst);
                return OperationResult.of(1);
            }));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> repository().executeOnProduct("A", p -> {
                secondEntered.set(true);
                return OperationResult.of(2);
            }));

            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(secondEntered).isFalse();

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(secondEntered).isTrue();
        }
    }

    @RepeatedTest(20)
    @DisplayName("RNF-6: a busy SKU does not block other SKUs")
    void differentSkusAreIndependent() throws Exception {
        stock("A", 10);
        stock("B", 10);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> blocked = executor.submit(() -> repository().executeOnProduct("A", p -> {
                firstEntered.countDown();
                awaitQuietly(releaseFirst);
                return OperationResult.of(1);
            }));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

            try {
                Future<Integer> other = executor.submit(() -> {
                    reserve("B", "OB", 1, T0);
                    return repository().availableUnits("B", T0);
                });
                assertThat(other.get(2, TimeUnit.SECONDS)).isEqualTo(9);
            } finally {
                releaseFirst.countDown();
            }
            blocked.get(5, TimeUnit.SECONDS);
        }
    }

    @RepeatedTest(20)
    @DisplayName("RNF-1: 200 concurrent reservations of 1 unit over 50 units sell exactly 50")
    void noOverselling() throws Exception {
        int stockUnits = 50;
        int threads = 200;
        stock("A", stockUnits);
        CyclicBarrier start = new CyclicBarrier(threads);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String orderId = "ORD-" + i;
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        reserve("A", orderId, 1, T0);
                        successes.incrementAndGet();
                    } catch (InsufficientStockException e) {
                        insufficient.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                get(future);
            }
        }

        assertThat(successes.get()).isEqualTo(stockUnits);
        assertThat(insufficient.get()).isEqualTo(threads - stockUnits);
        assertThat(repository().availableUnits("A", T0)).isZero();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void get(Future<?> future) throws Exception {
        try {
            future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }
}

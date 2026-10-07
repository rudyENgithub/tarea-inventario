package com.store.inventory.adapter.out.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.application.port.out.OperationResult;
import com.store.inventory.application.port.out.OrderIdConflictException;
import com.store.inventory.domain.alert.LowStockAlert;
import com.store.inventory.domain.policy.CategoryPolicy;
import com.store.inventory.domain.policy.StandardCategoryPolicy;
import com.store.inventory.testsupport.PostgresTestDatabase;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Comportamiento específico de SQL y de locking de {@link JdbcInventoryRepository}, verificado con consultas directas.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcInventoryRepositorySqlIT {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final CategoryPolicy STANDARD = new StandardCategoryPolicy();

    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private JdbcInventoryRepository repository;

    @BeforeEach
    void setUp() {
        PostgresTestDatabase.reset();
        var dataSource = PostgresTestDatabase.dataSource();
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new JdbcInventoryRepository(jdbc, tx);
    }

    private void stock(String sku, int units) {
        repository.registerIfAbsent(sku, ProductCategory.STANDARD);
        repository.executeOnProduct(sku, p -> {
            p.addStock(units);
            return OperationResult.of(units);
        }).orElseThrow();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    @DisplayName("RF-A4: a failing operation leaves on_hand, reservations and the outbox untouched")
    void rollbackLeavesTheDatabaseUntouched() {
        stock("A", 10);
        var boom = new InsufficientStockException("A", 99, 10);

        repository.executeOnProduct("A", "O1", p -> {
            p.reserve("O1", 3, STANDARD, T0);
            return new OperationResult<>(1, List.of(new LowStockAlert("A", 7, T0)));
        }).orElseThrow();
        assertThatThrownBy(() -> repository.executeOnProduct("A", "O2", p -> {
            p.addStock(5);
            p.reserve("O2", 3, STANDARD, T0);
            throw boom;
        })).isSameAs(boom);

        assertThat(jdbc.sql("SELECT on_hand FROM products WHERE sku = 'A'").query(Integer.class).single())
                .isEqualTo(10);
        assertThat(count("reservations")).isEqualTo(1);
        assertThat(count("alert_outbox")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT order_id FROM reservations").query(String.class).single()).isEqualTo("O1");
    }

    @Test
    @DisplayName("D6: the database refuses to move an order to another SKU")
    void orderIdConflictAtDatabaseLevel() {
        stock("A", 10);
        stock("B", 10);
        repository.executeOnProduct("A", "O1", p -> {
            p.reserve("O1", 2, STANDARD, T0);
            return OperationResult.of(1);
        }).orElseThrow();

        assertThatThrownBy(() -> repository.executeOnProduct("B", "O1", p -> {
            p.reserve("O1", 2, STANDARD, T0);
            return OperationResult.of(1);
        })).isInstanceOf(OrderIdConflictException.class);

        assertThat(jdbc.sql("SELECT sku FROM reservations WHERE order_id = 'O1'").query(String.class).single())
                .isEqualTo("A");
        assertThat(jdbc.sql("SELECT on_hand FROM products WHERE sku = 'B'").query(Integer.class).single())
                .isEqualTo(10);
    }

    @Test
    @DisplayName("D9: expiresAt written equals expiresAt read for 100 random microsecond instants")
    void microsecondRoundTrip() {
        stock("A", 1_000);
        Random random = new Random(42);
        for (int i = 0; i < 100; i++) {
            Instant now = Instant.parse("2026-01-01T00:00:00Z")
                    .plusSeconds(random.nextInt(86_400 * 365))
                    .plusNanos(random.nextInt(1_000_000_000));
            String orderId = "R" + i;
            Instant expected = repository.executeOnProduct("A", orderId, p ->
                    OperationResult.of(p.reserve(orderId, 1, STANDARD, now).reservation().expiresAt())).orElseThrow();

            assertThat(repository.findReservation(orderId).orElseThrow().expiresAt()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("RNF-2: the product row is really locked: a second transaction on the same SKU waits")
    void rowLockBlocksTheSameSkuOnly() throws Exception {
        stock("A", 10);
        stock("B", 10);
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> holder = executor.submit(() -> repository.executeOnProduct("A", p -> {
                aEntered.countDown();
                await(release);
                return OperationResult.of(1);
            }));
            assertThat(aEntered.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> sameSku = executor.submit(() -> repository.executeOnProduct("A", p -> OperationResult.of(2)));
            Future<?> otherSku = executor.submit(() -> repository.executeOnProduct("B", p -> OperationResult.of(3)));

            assertThat(otherSku.get(5, TimeUnit.SECONDS)).isNotNull();
            assertThat(sameSku.isDone()).isFalse();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            sameSku.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("lock_timeout: a transaction waiting too long for the row lock fails with a locking exception")
    void lockTimeout() throws Exception {
        stock("A", 10);
        var impatient = new JdbcInventoryRepository(jdbc, tx, Duration.ofMillis(200));
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> holder = executor.submit(() -> repository.executeOnProduct("A", p -> {
                aEntered.countDown();
                await(release);
                return OperationResult.of(1);
            }));
            assertThat(aEntered.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> impatient.executeOnProduct("A", p -> OperationResult.of(2)))
                        .isInstanceOf(PessimisticLockingFailureException.class);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("RNF-1: 200 concurrent reservations over 50 units leave on_hand minus reserved equal to zero")
    void noOversellingAtDatabaseLevel() throws Exception {
        stock("A", 50);
        int successes = 0;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                String orderId = "O" + i;
                futures.add(executor.submit(() -> {
                    try {
                        repository.executeOnProduct("A", orderId, p -> {
                            p.reserve(orderId, 1, STANDARD, T0);
                            return OperationResult.of(1);
                        });
                        return true;
                    } catch (InsufficientStockException e) {
                        return false;
                    }
                }));
            }
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
        }

        assertThat(successes).isEqualTo(50);
        assertThat(jdbc.sql("SELECT on_hand - COALESCE(SUM(r.quantity), 0) FROM products p "
                + "LEFT JOIN reservations r ON r.sku = p.sku AND r.status = 'ACTIVE' WHERE p.sku = 'A' GROUP BY p.on_hand")
                .query(Long.class).single()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

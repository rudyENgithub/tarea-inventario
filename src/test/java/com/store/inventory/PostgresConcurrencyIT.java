package com.store.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.testsupport.ConcurrentTestSupport;
import com.store.inventory.testsupport.ConcurrentTestSupport.Outcome;
import com.store.inventory.testsupport.InvariantChecker;
import com.store.inventory.testsupport.TwoInstanceHarness;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dos instancias en proceso que comparten PostgreSQL. Ejecútalo con
 * {@code mvn -q verify -Dit.test=PostgresConcurrencyIT}.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresConcurrencyIT {

    private static TwoInstanceHarness harness;

    @BeforeAll
    static void start() {
        harness = TwoInstanceHarness.create();
    }

    @AfterAll
    static void stop() {
        harness.a().close();
        harness.b().close();
        InvariantChecker.assertNoInProgress(harness.dataSource());
        harness.close();
    }

    @BeforeEach
    void reset() {
        harness.reset();
    }

    @RepeatedTest(5)
    @DisplayName("RNF-1 no oversell across instances")
    void noOversell() {
        var sku = "HOT";
        seed(sku, 100);
        List<Outcome<Reservation>> outcomes = ConcurrentTestSupport.runConcurrently(1000, Duration.ofSeconds(60),
                i -> () -> instance(i).reserve("O" + i, sku, 1));
        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(100);
        assertThat(outcomes.stream().filter(o -> o.failure() instanceof InsufficientStockException).count())
                .isEqualTo(900);
        assertThat(outcomes.stream().filter(o -> !o.succeeded() && !(o.failure() instanceof InsufficientStockException)))
                .isEmpty();
        assertThat(harness.a().service().available(sku)).isEqualTo(0);
        InvariantChecker.assertConsistent(harness.dataSource(), harness.clock().instant());
    }

    @RepeatedTest(5)
    @DisplayName("RNF-1 mixed quantities")
    void mixedQuantities() {
        seed("MIX", 200);
        Random random = new Random(1);
        int[] requested = new int[1000];
        for (int i = 0; i < requested.length; i++) {
            requested[i] = 1 + random.nextInt(5);
        }
        List<Outcome<Reservation>> outcomes = ConcurrentTestSupport.runConcurrently(1000, Duration.ofSeconds(60),
                i -> () -> instance(i).reserve("M" + i, "MIX", requested[i]));
        int granted = outcomes.stream().filter(Outcome::succeeded).mapToInt(o -> o.value().quantity()).sum();
        assertThat(granted).isLessThanOrEqualTo(200);
        assertThat(harness.a().service().available("MIX")).isEqualTo(200 - granted);
        InvariantChecker.assertConsistent(harness.dataSource(), harness.clock().instant());
    }

    @RepeatedTest(5)
    @DisplayName("AD-3 idempotent reserve across instances")
    void idempotentReserve() {
        seed("IDEM", 10);
        List<Outcome<Reservation>> outcomes = ConcurrentTestSupport.runConcurrently(200, Duration.ofSeconds(30),
                i -> () -> instance(i).reserve("SAME", "IDEM", 3));
        assertThat(outcomes).allMatch(Outcome::succeeded);
        Reservation first = outcomes.getFirst().value();
        assertThat(outcomes).allSatisfy(o -> assertThat(o.value()).isEqualTo(first));
        assertThat(harness.a().service().available("IDEM")).isEqualTo(7);
        Integer rows = harness.jdbc().sql("SELECT count(*) FROM reservations").query(Integer.class).single();
        assertThat(rows).isEqualTo(1);
        InvariantChecker.assertConsistent(harness.dataSource(), harness.clock().instant());
    }

    @RepeatedTest(5)
    @DisplayName("RF-C3 concurrent confirm")
    void concurrentConfirm() {
        seed("CONF", 5);
        harness.a().service().reserve("PAY", "CONF", 2);
        List<Outcome<Void>> outcomes = ConcurrentTestSupport.runConcurrently(100,
                i -> () -> {
                    instance(i).confirm("PAY");
                    return null;
                });
        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(harness.a().service().available("CONF")).isEqualTo(3);
        Integer onHand = harness.jdbc().sql("SELECT on_hand FROM products WHERE sku = 'CONF'")
                .query(Integer.class).single();
        assertThat(onHand).isEqualTo(3);
        InvariantChecker.assertConsistent(harness.dataSource(), harness.clock().instant());
    }

    @RepeatedTest(5)
    @DisplayName("RF-A2 single alert across instances")
    void singleAlert() {
        seed("ALRT", 100);
        ConcurrentTestSupport.runConcurrently(300, Duration.ofSeconds(30),
                i -> () -> instance(i).reserve("A" + i, "ALRT", 1));
        ConcurrentTestSupport.awaitCondition(() -> outboxCount() == 1 && "DELIVERED".equals(outboxStatus()),
                Duration.ofSeconds(5));
        assertThat(harness.a().alerts().count() + harness.b().alerts().count()).isEqualTo(1);
        Integer attempts = harness.jdbc().sql("SELECT attempts FROM alert_outbox").query(Integer.class).single();
        assertThat(attempts).isEqualTo(1);
        InvariantChecker.assertConsistent(harness.dataSource(), harness.clock().instant());
    }

    @RepeatedTest(5)
    @DisplayName("RF-A3 rearm after restock")
    void rearm() {
        seed("REARM", 100);
        ConcurrentTestSupport.runConcurrently(300, Duration.ofSeconds(30),
                i -> () -> instance(i).reserve("R" + i, "REARM", 1));
        ConcurrentTestSupport.awaitCondition(() -> outboxCount() == 1, Duration.ofSeconds(5));
        harness.a().service().addStock("REARM", 50);
        ConcurrentTestSupport.runConcurrently(100, Duration.ofSeconds(30),
                i -> () -> instance(i).reserve("S" + i, "REARM", 1));
        ConcurrentTestSupport.awaitCondition(() -> outboxCount() == 2, Duration.ofSeconds(5));
        InvariantChecker.assertConsistent(harness.dataSource(), harness.clock().instant());
    }

    @RepeatedTest(5)
    @DisplayName("D6 orderId race across SKUs")
    void orderIdConflictAcrossSkus() {
        seed("SKU-A", 5);
        seed("SKU-B", 5);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Thread t1 = Thread.ofVirtual().start(() -> {
            await(start);
            try {
                harness.a().service().reserve("X", "SKU-A", 1);
                ok.incrementAndGet();
            } catch (IllegalArgumentException e) {
                conflicts.incrementAndGet();
            }
        });
        Thread t2 = Thread.ofVirtual().start(() -> {
            await(start);
            try {
                harness.b().service().reserve("X", "SKU-B", 1);
                ok.incrementAndGet();
            } catch (IllegalArgumentException e) {
                conflicts.incrementAndGet();
            }
        });
        start.countDown();
        join(t1);
        join(t2);
        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(harness.jdbc().sql("SELECT count(*) FROM reservations WHERE order_id = 'X'")
                .query(Integer.class).single()).isEqualTo(1);
        InvariantChecker.assertConsistent(harness.dataSource(), harness.clock().instant());
    }

    @RepeatedTest(5)
    @DisplayName("RNF-6 multi-SKU")
    void multiSku() {
        for (int s = 0; s < 50; s++) {
            seed("S" + s, 20);
        }
        Random random = new Random(42);
        int[] skus = new int[5000];
        for (int i = 0; i < skus.length; i++) {
            skus[i] = random.nextInt(50);
        }
        List<Outcome<Reservation>> outcomes = ConcurrentTestSupport.runConcurrently(5000, Duration.ofSeconds(90),
                i -> () -> instance(i).reserve("T" + i, "S" + skus[i], 1));
        int[] granted = new int[50];
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i).succeeded()) {
                granted[skus[i]]++;
            }
        }
        for (int s = 0; s < 50; s++) {
            assertThat(granted[s]).isLessThanOrEqualTo(20);
        }
        InvariantChecker.assertConsistent(harness.dataSource(), harness.clock().instant());
    }

    @RepeatedTest(5)
    @DisplayName("RF-E1 expiration then reuse")
    void expiration() {
        seed("EXP", 10);
        for (int i = 0; i < 10; i++) {
            harness.a().service().reserve("E" + i, "EXP", 1);
        }
        harness.clock().advance(Duration.ofMinutes(15));
        List<Outcome<Reservation>> outcomes = ConcurrentTestSupport.runConcurrently(100, Duration.ofSeconds(30),
                i -> () -> instance(i).reserve("N" + i, "EXP", 1));
        assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(10);
        InvariantChecker.assertConsistent(harness.dataSource(), harness.clock().instant());
    }

    @Test
    @DisplayName("RNF-2 small pool")
    void smallPool() {
        try (TwoInstanceHarness small = TwoInstanceHarness.create(3)) {
            small.a().service().registerProduct("POOL", ProductCategory.STANDARD);
            small.a().service().addStock("POOL", 50);
            List<Outcome<Reservation>> outcomes = ConcurrentTestSupport.runConcurrently(2000, Duration.ofSeconds(30),
                    i -> () -> (i % 2 == 0 ? small.a() : small.b()).service().reserve("P" + i, "POOL", 1));
            assertThat(outcomes).allMatch(o -> o.succeeded() || o.failure() instanceof InsufficientStockException);
            InvariantChecker.assertConsistent(small.dataSource(), small.clock().instant());
        }
    }

    @Test
    @DisplayName("RNF-6 lock on A does not block B")
    void lockDoesNotBlockOtherSku() throws Exception {
        seed("A", 10);
        seed("B", 100);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch locked = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            try (Connection connection = harness.dataSource().getConnection()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT sku FROM products WHERE sku = 'A' FOR UPDATE");
                    locked.countDown();
                    held.await(30, TimeUnit.SECONDS);
                }
                connection.rollback();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();
        ConcurrentTestSupport.runConcurrently(100, Duration.ofSeconds(5),
                i -> () -> instance(i).reserve("B" + i, "B", 1));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        held.countDown();
        holder.join(TimeUnit.SECONDS.toMillis(5));
        InvariantChecker.assertConsistent(harness.dataSource(), harness.clock().instant());
    }

    @Test
    @DisplayName("RNF-5 close instance A, B finishes alerts")
    void closeDoesNotLoseAlerts() {
        try (TwoInstanceHarness local = TwoInstanceHarness.create(10, Duration.ofSeconds(2))) {
            local.a().service().registerProduct("CLOSE", ProductCategory.STANDARD);
            local.a().service().addStock("CLOSE", 10);
            local.a().service().reserve("CL1", "CLOSE", 5);
            local.a().close();
            local.clock().advance(Duration.ofSeconds(3));
            ConcurrentTestSupport.awaitCondition(() -> {
                String status = local.jdbc().sql("SELECT status FROM alert_outbox ORDER BY id LIMIT 1")
                        .query(String.class).optional().orElse("");
                return "DELIVERED".equals(status);
            }, Duration.ofSeconds(5));
            InvariantChecker.assertNoInProgress(local.dataSource());
        }
    }

    private void seed(String sku, int stock) {
        harness.a().service().registerProduct(sku, ProductCategory.STANDARD);
        harness.a().service().addStock(sku, stock);
    }

    private com.store.inventory.api.InventoryService instance(int i) {
        return (i % 2 == 0 ? harness.a() : harness.b()).service();
    }

    private int outboxCount() {
        return harness.jdbc().sql("SELECT count(*) FROM alert_outbox").query(Integer.class).single();
    }

    private String outboxStatus() {
        return harness.jdbc().sql("SELECT status FROM alert_outbox ORDER BY id LIMIT 1")
                .query(String.class).optional().orElse("");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}

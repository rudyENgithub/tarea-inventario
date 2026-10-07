package com.store.inventory.adapter.out.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.store.inventory.application.port.out.AlertOutbox;
import com.store.inventory.application.port.out.AlertOutboxContractTest;
import com.store.inventory.application.port.out.PendingAlert;
import com.store.inventory.domain.alert.LowStockAlert;
import com.store.inventory.testsupport.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAlertOutboxIT extends AlertOutboxContractTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private JdbcClient jdbc;
    private JdbcAlertOutbox outbox;

    @BeforeEach
    void setUp() {
        PostgresTestDatabase.reset();
        jdbc = JdbcClient.create(PostgresTestDatabase.dataSource());
        outbox = new JdbcAlertOutbox(jdbc);
    }

    @Override
    protected AlertOutbox outbox() {
        return outbox;
    }

    @Override
    protected long enqueue(LowStockAlert alert) {
        return jdbc.sql("INSERT INTO alert_outbox(sku, available_units, occurred_at, status, next_attempt_at) "
                        + "VALUES (:sku, :units, :at, 'PENDING', :at) RETURNING id")
                .param("sku", alert.sku())
                .param("units", alert.availableUnits())
                .param("at", SqlTime.toDb(alert.occurredAt()))
                .query(Long.class)
                .single();
    }

    @Override
    protected String statusOf(long id) {
        return jdbc.sql("SELECT status FROM alert_outbox WHERE id = :id").param("id", id).query(String.class).single();
    }

    @RepeatedTest(10)
    @DisplayName("RNF-5: two instances with 16 consumers deliver each of 500 alerts exactly once")
    void multiConsumerClaimsAreDisjointAndComplete() throws Exception {
        var secondInstance = new JdbcAlertOutbox(JdbcClient.create(PostgresTestDatabase.dataSource()));
        Set<Long> expected = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            expected.add(enqueue(new LowStockAlert("SKU-" + i, 3, T0)));
        }
        int consumers = 16;
        CyclicBarrier start = new CyclicBarrier(consumers);
        List<Long> received = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int c = 0; c < consumers; c++) {
                AlertOutbox consumer = c % 2 == 0 ? outbox : secondInstance;
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    List<Long> mine = new ArrayList<>();
                    List<PendingAlert> batch;
                    while (!(batch = consumer.claimDue(T0, 25, LEASE)).isEmpty()) {
                        batch.forEach(p -> mine.add(p.id()));
                    }
                    return mine;
                }));
            }
            for (Future<List<Long>> future : futures) {
                received.addAll(future.get(30, TimeUnit.SECONDS));
            }
        }

        assertThat(received).hasSize(500).doesNotHaveDuplicates();
        assertThat(new HashSet<>(received)).isEqualTo(expected);
    }

    @Test
    @DisplayName("SKIP LOCKED: rows locked by another transaction are skipped instead of waited for")
    void skipLockedDoesNotBlock() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(enqueue(new LowStockAlert("SKU-" + i, 3, T0)));
        }
        try (Connection holder = PostgresTestDatabase.dataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM alert_outbox ORDER BY id LIMIT 10 FOR UPDATE")) {
                lock.executeQuery().close();
            }
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<List<PendingAlert>> claim = executor.submit(() -> outbox.claimDue(T0, 5, LEASE));

                List<PendingAlert> claimed = claim.get(2, TimeUnit.SECONDS);

                assertThat(claimed).extracting(PendingAlert::id).containsExactlyElementsOf(ids.subList(10, 15));
            } finally {
                holder.rollback();
            }
        }
    }

    @Test
    @DisplayName("a consumer that dies after claiming does not lose the alert: it is reclaimed after the lease")
    void abandonedClaimIsRecovered() {
        var other = new JdbcAlertOutbox(JdbcClient.create(PostgresTestDatabase.dataSource()));
        long id = enqueue(new LowStockAlert("A", 3, T0));
        outbox.claimDue(T0, 1, LEASE);

        assertThat(other.claimDue(T0.plusSeconds(29), 1, LEASE)).isEmpty();
        List<PendingAlert> reclaimed = other.claimDue(T0.plus(LEASE), 1, LEASE);

        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).id()).isEqualTo(id);
        assertThat(reclaimed.get(0).attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("occurred_at keeps its microseconds and last_error is cut at 1000 characters")
    void precisionAndTruncation() {
        Instant occurredAt = Instant.parse("2026-01-01T00:00:00.123456Z");
        long id = enqueue(new LowStockAlert("A", 3, occurredAt));

        List<PendingAlert> claimed = outbox.claimDue(occurredAt, 1, LEASE);
        outbox.markRetry(id, occurredAt.plusSeconds(1), "x".repeat(5_000));

        assertThat(claimed.get(0).alert().occurredAt()).isEqualTo(occurredAt);
        assertThat(jdbc.sql("SELECT length(last_error) FROM alert_outbox WHERE id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("the claim query can use the partial indexes instead of scanning the whole table")
    void claimQueryAvoidsSequentialScan() throws Exception {
        jdbc.sql("INSERT INTO alert_outbox(sku, available_units, occurred_at, status, next_attempt_at, delivered_at) "
                + "SELECT 'S' || g, 1, now(), 'DELIVERED', now(), now() FROM generate_series(1, 10000) g").update();
        for (int i = 0; i < 20; i++) {
            enqueue(new LowStockAlert("P" + i, 1, T0));
        }
        try (Connection connection = PostgresTestDatabase.dataSource().getConnection()) {
            connection.createStatement().execute("ANALYZE alert_outbox");
            StringBuilder plan = new StringBuilder();
            try (PreparedStatement explain = connection.prepareStatement(
                    "EXPLAIN SELECT id FROM alert_outbox WHERE (status = 'PENDING' AND next_attempt_at <= ?) "
                            + "OR (status = 'IN_PROGRESS' AND lease_until <= ?) ORDER BY id LIMIT 25")) {
                var now = SqlTime.toDb(T0);
                explain.setObject(1, now);
                explain.setObject(2, now);
                try (ResultSet rows = explain.executeQuery()) {
                    while (rows.next()) {
                        plan.append(rows.getString(1)).append('\n');
                    }
                }
            }

            assertThat(plan.toString()).doesNotContain("Seq Scan");
        }
    }
}

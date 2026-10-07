package com.store.inventory.testsupport;

import com.store.inventory.adapter.out.notification.CompositeStockAlertListener;
import com.store.inventory.adapter.out.notification.SafeStockAlertListener;
import com.store.inventory.adapter.out.persistence.jdbc.JdbcAlertOutbox;
import com.store.inventory.adapter.out.persistence.jdbc.JdbcInventoryRepository;
import com.store.inventory.api.InventoryService;
import com.store.inventory.application.DefaultInventoryService;
import com.store.inventory.application.alert.AlertDispatcher;
import com.store.inventory.application.alert.AlertRelay;
import com.store.inventory.application.alert.AlertRetryPolicy;
import com.store.inventory.application.alert.AsyncAlertDispatchTrigger;
import com.store.inventory.application.port.out.AlertChannel;
import com.store.inventory.domain.alert.LowStockDetector;
import com.store.inventory.domain.alert.LowStockThreshold;
import com.store.inventory.domain.policy.CategoryPolicyRegistry;
import com.store.inventory.domain.policy.DefaultCategoryPolicies;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dos instancias independientes de la aplicación que comparten una base de datos PostgreSQL (sin locks de JVM
 * compartidos).
 */
public final class TwoInstanceHarness implements AutoCloseable {

    public record Instance(InventoryService service, RecordingAlertListener alerts, AlertRelay relay) {
        public void close() {
            relay.close();
        }
    }

    private final HikariDataSource dataSource;
    private final MutableClock clock;
    private final Instance a;
    private final Instance b;
    private final JdbcClient jdbc;

    public static TwoInstanceHarness create() {
        return create(10, Duration.ofSeconds(30));
    }

    public static TwoInstanceHarness create(int poolSize) {
        return create(poolSize, Duration.ofSeconds(30));
    }

    public static TwoInstanceHarness create(int poolSize, Duration dispatchLease) {
        PostgresTestDatabase.migrate();
        HikariDataSource dataSource = PostgresTestDatabase.newDataSource(poolSize);
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        return new TwoInstanceHarness(dataSource, clock, dispatchLease);
    }

    private TwoInstanceHarness(HikariDataSource dataSource, MutableClock clock, Duration dispatchLease) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.jdbc = JdbcClient.create(dataSource);
        JdbcClient sharedJdbc = JdbcClient.create(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcInventoryRepository repositoryA = new JdbcInventoryRepository(sharedJdbc, tx);
        JdbcInventoryRepository repositoryB = new JdbcInventoryRepository(JdbcClient.create(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        JdbcAlertOutbox outboxA = new JdbcAlertOutbox(sharedJdbc);
        JdbcAlertOutbox outboxB = new JdbcAlertOutbox(JdbcClient.create(dataSource));
        this.a = instance("a", repositoryA, outboxA, dispatchLease);
        this.b = instance("b", repositoryB, outboxB, dispatchLease);
        reset();
    }

    public Instance a() {
        return a;
    }

    public Instance b() {
        return b;
    }

    public MutableClock clock() {
        return clock;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public void reset() {
        PostgresTestDatabase.reset();
        clock.set(Instant.parse("2026-01-01T00:00:00Z"));
        a.alerts().clear();
        b.alerts().clear();
    }

    @Override
    public void close() {
        a.close();
        b.close();
        dataSource.close();
    }

    private Instance instance(String name, JdbcInventoryRepository repository, JdbcAlertOutbox outbox,
            Duration dispatchLease) {
        RecordingAlertListener listener = new RecordingAlertListener();
        var composite = new CompositeStockAlertListener(List.of(
                new SafeStockAlertListener(AlertChannel.of(name, listener))));
        var dispatcher = new AlertDispatcher(outbox, composite, clock, AlertRetryPolicy.defaults(), 50, dispatchLease);
        var relay = new AlertRelay(dispatcher::dispatchPending, Duration.ofMillis(50));
        relay.start();
        var service = new DefaultInventoryService(repository,
                CategoryPolicyRegistry.of(DefaultCategoryPolicies.all()),
                new LowStockDetector(LowStockThreshold.DEFAULT),
                new AsyncAlertDispatchTrigger(relay),
                clock);
        return new Instance(service, listener, relay);
    }
}

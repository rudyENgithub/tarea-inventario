package com.store.inventory.adapter.out.persistence.jdbc;

import com.store.inventory.application.port.out.InventoryRepository;
import com.store.inventory.application.port.out.InventoryRepositoryContractTest;
import com.store.inventory.domain.alert.LowStockAlert;
import com.store.inventory.testsupport.PostgresTestDatabase;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ejecuta el contrato compartido del repositorio contra PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcInventoryRepositoryIT extends InventoryRepositoryContractTest {

    private InventoryRepository repository;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        PostgresTestDatabase.reset();
        var dataSource = PostgresTestDatabase.dataSource();
        jdbc = JdbcClient.create(dataSource);
        repository = new JdbcInventoryRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Override
    protected InventoryRepository repository() {
        return repository;
    }

    @Override
    protected List<LowStockAlert> enqueuedAlerts() {
        return jdbc.sql("SELECT sku, available_units, occurred_at FROM alert_outbox ORDER BY id")
                .query((rs, rowNum) -> new LowStockAlert(rs.getString("sku"), rs.getInt("available_units"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}

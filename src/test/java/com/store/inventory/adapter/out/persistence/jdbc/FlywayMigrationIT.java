package com.store.inventory.adapter.out.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.store.inventory.testsupport.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIT {

    private static final String SCHEMA = "flyway_it";

    @Test
    void migratesAnEmptyDatabaseAndIsIdempotent() throws Exception {
        var dataSource = PostgresTestDatabase.dataSource();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load();

        MigrateResult first = flyway.migrate();
        MigrateResult second = flyway.migrate();

        assertThat(first.migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(second.migrationsExecuted).isZero();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(tablesIn(SCHEMA)).containsExactlyInAnyOrder("products", "reservations", "alert_outbox",
                "flyway_schema_history");
    }

    private static java.util.List<String> tablesIn(String schema) throws Exception {
        var tables = new java.util.ArrayList<String>();
        try (Connection connection = PostgresTestDatabase.dataSource().getConnection();
                var statement = connection.prepareStatement(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = ?")) {
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
        }
        return tables;
    }
}

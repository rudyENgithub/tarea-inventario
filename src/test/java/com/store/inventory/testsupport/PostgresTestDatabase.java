package com.store.inventory.testsupport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Un contenedor PostgreSQL compartido por todas las pruebas de integración de la JVM. Arranca de forma perezosa en el
 * primer uso y el reaper de Testcontainers lo elimina cuando la JVM termina. Llama a {@link #reset()} antes de cada
 * prueba.
 */
public final class PostgresTestDatabase {

    private static final String IMAGE = "postgres:17-alpine";
    private static final int POOL_SIZE = 20;

    private static PostgreSQLContainer container;
    private static HikariDataSource dataSource;

    private PostgresTestDatabase() {
    }

    /** {@code DataSource} con pool compartido sobre la base de datos ya migrada. */
    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            container = new PostgreSQLContainer(IMAGE);
            container.start();
            dataSource = newDataSource(POOL_SIZE);
            migrate();
        }
        return dataSource;
    }

    /**
     * Un pool nuevo e independiente (para pruebas que simulan varias instancias de la aplicación). El llamador debe
     * cerrarlo.
     */
    public static HikariDataSource newDataSource(int maximumPoolSize) {
        ensureStarted();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl());
        config.setUsername(container.getUsername());
        config.setPassword(container.getPassword());
        config.setMaximumPoolSize(maximumPoolSize);
        config.setConnectionTimeout(30_000);
        return new HikariDataSource(config);
    }

    public static synchronized String jdbcUrl() {
        ensureStarted();
        return container.getJdbcUrl();
    }

    public static synchronized String username() {
        ensureStarted();
        return container.getUsername();
    }

    public static synchronized String password() {
        ensureStarted();
        return container.getPassword();
    }

    /** Aplica las migraciones de Flyway de {@code classpath:db/migration}. Idempotente. */
    public static void migrate() {
        Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration").load().migrate();
    }

    /** Vacía todas las tablas para que la siguiente prueba empiece desde un estado limpio. */
    public static void reset() {
        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE alert_outbox, reservations, products RESTART IDENTITY CASCADE");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reset the test database", e);
        }
    }

    private static synchronized void ensureStarted() {
        if (container == null) {
            dataSource();
        }
    }
}

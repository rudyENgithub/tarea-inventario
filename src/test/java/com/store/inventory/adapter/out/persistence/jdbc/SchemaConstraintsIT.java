package com.store.inventory.adapter.out.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.testsupport.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class SchemaConstraintsIT {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";

    @BeforeEach
    void reset() {
        PostgresTestDatabase.reset();
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = PostgresTestDatabase.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void assertViolation(String sql, String sqlState) {
        assertThatThrownBy(() -> execute(sql)).isInstanceOfSatisfying(SQLException.class,
                e -> assertThat(e.getSQLState()).as(e.getMessage()).isEqualTo(sqlState));
    }

    private static void insertProduct(String sku) throws SQLException {
        execute("INSERT INTO products(sku, category, on_hand) VALUES ('" + sku + "', 'STANDARD', 10)");
    }

    @Test
    @DisplayName("AD-7: negative on_hand is rejected")
    void negativeOnHand() {
        assertViolation("INSERT INTO products(sku, category, on_hand) VALUES ('A', 'STANDARD', -1)", CHECK_VIOLATION);
    }

    @Test
    void nonPositiveReservationQuantity() throws SQLException {
        insertProduct("A");

        assertViolation("INSERT INTO reservations(order_id, sku, quantity, status, expires_at) "
                + "VALUES ('O1', 'A', 0, 'ACTIVE', now())", CHECK_VIOLATION);
    }

    @Test
    @DisplayName("RF-E2: EXPIRED is derived from the clock and can never be stored")
    void expiredStatusIsRejected() throws SQLException {
        insertProduct("A");

        assertViolation("INSERT INTO reservations(order_id, sku, quantity, status, expires_at) "
                + "VALUES ('O1', 'A', 1, 'EXPIRED', now())", CHECK_VIOLATION);
    }

    @Test
    void unknownOutboxStatusIsRejected() {
        assertViolation("INSERT INTO alert_outbox(sku, available_units, occurred_at, status, next_attempt_at) "
                + "VALUES ('A', 1, now(), 'X', now())", CHECK_VIOLATION);
    }

    @Test
    void reservationNeedsAnExistingProduct() {
        assertViolation("INSERT INTO reservations(order_id, sku, quantity, status, expires_at) "
                + "VALUES ('O1', 'MISSING', 1, 'ACTIVE', now())", FOREIGN_KEY_VIOLATION);
    }

    @Test
    @DisplayName("RF-R4: an order id is globally unique")
    void orderIdIsUnique() throws SQLException {
        insertProduct("A");
        insertProduct("B");
        execute("INSERT INTO reservations(order_id, sku, quantity, status, expires_at) "
                + "VALUES ('O1', 'A', 1, 'ACTIVE', now())");

        assertViolation("INSERT INTO reservations(order_id, sku, quantity, status, expires_at) "
                + "VALUES ('O1', 'B', 1, 'ACTIVE', now())", UNIQUE_VIOLATION);
    }

    @Test
    void expectedIndexesExist() throws SQLException {
        List<String> indexes = new ArrayList<>();
        try (Connection connection = PostgresTestDatabase.dataSource().getConnection();
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")) {
            while (rows.next()) {
                indexes.add(rows.getString(1));
            }
        }

        assertThat(indexes).contains("ix_reservations_active", "ix_outbox_pending", "ix_outbox_in_progress");
    }
}

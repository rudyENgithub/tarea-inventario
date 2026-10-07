package com.store.inventory.adapter.out.persistence.jdbc;

import com.store.inventory.application.port.out.AlertOutbox;
import com.store.inventory.application.port.out.PendingAlert;
import com.store.inventory.domain.alert.LowStockAlert;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link AlertOutbox} sobre PostgreSQL. Cada método es una única sentencia atómica (autocommit): ninguna transacción
 * permanece abierta mientras se entrega una alerta. {@code claimDue} usa {@code FOR UPDATE SKIP LOCKED}, así que
 * varias instancias reclaman entradas distintas sin esperarse entre sí, y un lease reemplaza cualquier "reaper" para
 * consumidores caídos.
 *
 * <p>Las alertas las inserta {@link JdbcInventoryRepository} dentro de la transacción de la reserva; esta clase solo
 * consume.
 */
public final class JdbcAlertOutbox implements AlertOutbox {

    private final JdbcClient jdbc;

    public JdbcAlertOutbox(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<PendingAlert> claimDue(Instant now, int limit, Duration lease) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lease, "lease");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1: " + limit);
        }
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive: " + lease);
        }
        return jdbc.sql("""
                        WITH due AS (
                          SELECT id FROM alert_outbox
                          WHERE (status = 'PENDING' AND next_attempt_at <= :now)
                             OR (status = 'IN_PROGRESS' AND lease_until <= :now)
                          ORDER BY id
                          LIMIT :limit
                          FOR UPDATE SKIP LOCKED)
                        UPDATE alert_outbox o
                           SET status = 'IN_PROGRESS', lease_until = :leaseUntil, attempts = o.attempts + 1
                          FROM due WHERE o.id = due.id
                        RETURNING o.id, o.sku, o.available_units, o.occurred_at, o.attempts
                        """)
                .param("now", SqlTime.toDb(now))
                .param("limit", limit)
                .param("leaseUntil", SqlTime.toDb(now.plus(lease)))
                .query((rs, rowNum) -> new PendingAlert(rs.getLong("id"),
                        new LowStockAlert(rs.getString("sku"), rs.getInt("available_units"),
                                SqlTime.fromDb(rs.getObject("occurred_at", OffsetDateTime.class))),
                        rs.getInt("attempts")))
                .list()
                .stream()
                .sorted(Comparator.comparingLong(PendingAlert::id))
                .toList();
    }

    @Override
    public void markDelivered(long id, Instant now) {
        jdbc.sql("UPDATE alert_outbox SET status = 'DELIVERED', delivered_at = :now, lease_until = NULL "
                        + "WHERE id = :id AND status IN ('PENDING', 'IN_PROGRESS')")
                .param("now", SqlTime.toDb(now))
                .param("id", id)
                .update();
    }

    @Override
    public void markRetry(long id, Instant nextAttemptAt, String error) {
        jdbc.sql("UPDATE alert_outbox SET status = 'PENDING', next_attempt_at = :next, lease_until = NULL, "
                        + "last_error = left(:error, 1000) WHERE id = :id AND status = 'IN_PROGRESS'")
                .param("next", SqlTime.toDb(nextAttemptAt))
                .param("error", error)
                .param("id", id)
                .update();
    }

    @Override
    public void markFailed(long id, String error) {
        jdbc.sql("UPDATE alert_outbox SET status = 'FAILED', lease_until = NULL, last_error = left(:error, 1000) "
                        + "WHERE id = :id AND status IN ('PENDING', 'IN_PROGRESS')")
                .param("error", error)
                .param("id", id)
                .update();
    }
}

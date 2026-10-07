package com.store.inventory.adapter.out.persistence.jdbc;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.application.port.out.InventoryRepository;
import com.store.inventory.application.port.out.OperationResult;
import com.store.inventory.application.port.out.OrderIdConflictException;
import com.store.inventory.application.port.out.StockOperation;
import com.store.inventory.domain.alert.LowStockAlert;
import com.store.inventory.domain.stock.ProductStock;
import com.store.inventory.domain.stock.ReservationStatus;
import com.store.inventory.domain.stock.StockChanges;
import com.store.inventory.domain.stock.StockReservation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link InventoryRepository} sobre PostgreSQL. La sección crítica por SKU es una transacción corta que bloquea la
 * fila del producto con {@code SELECT ... FOR UPDATE} (READ COMMITTED), lo que serializa las operaciones sobre el
 * mismo SKU entre cualquier número de instancias de la aplicación, mientras que SKUs distintos nunca se bloquean
 * entre sí. Aquí no hay locks de la JVM.
 *
 * <p>El {@link TransactionTemplate} recibido debe estar respaldado por un gestor de transacciones del mismo
 * {@code DataSource} que usa el {@link JdbcClient}; esta clase deriva su propia copia forzada a
 * {@code READ_COMMITTED} y {@code REQUIRED}. Los fallos de la operación del llamador revierten todo y se propagan
 * sin envolver.
 */
public final class JdbcInventoryRepository implements InventoryRepository {

    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(5);

    /** SQLSTATE que lanza PostgreSQL cuando vence {@code lock_timeout}. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private static final String SELECT_RESERVATION =
            "SELECT order_id, sku, quantity, expires_at, status FROM reservations ";

    private record ProductRow(String sku, ProductCategory category, int onHand, boolean alertArmed) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final String lockTimeoutSetting;

    public JdbcInventoryRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this(jdbc, tx, DEFAULT_LOCK_TIMEOUT);
    }

    public JdbcInventoryRepository(JdbcClient jdbc, TransactionTemplate tx, Duration lockTimeout) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(lockTimeout, "lockTimeout");
        if (lockTimeout.isZero() || lockTimeout.isNegative()) {
            throw new IllegalArgumentException("lockTimeout must be positive: " + lockTimeout);
        }
        this.tx = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager(), "transaction manager"), tx);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.lockTimeoutSetting = lockTimeout.toMillis() + "ms";
    }

    @Override
    public ProductCategory registerIfAbsent(String sku, ProductCategory category) {
        requireText(sku, "sku");
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        jdbc.sql("INSERT INTO products(sku, category, on_hand, alert_armed) VALUES (:sku, :category, 0, true) "
                        + "ON CONFLICT (sku) DO NOTHING")
                .param("sku", sku)
                .param("category", category.name())
                .update();
        String stored = jdbc.sql("SELECT category FROM products WHERE sku = :sku")
                .param("sku", sku)
                .query(String.class)
                .single();
        return parseCategory(stored);
    }

    @Override
    public Optional<StockReservation> findReservation(String orderId) {
        requireText(orderId, "orderId");
        return jdbc.sql(SELECT_RESERVATION + "WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(JdbcInventoryRepository::mapReservation)
                .optional();
    }

    @Override
    public int availableUnits(String sku, Instant now) {
        return jdbc.sql("SELECT p.on_hand - COALESCE((SELECT SUM(r.quantity) FROM reservations r "
                        + "WHERE r.sku = p.sku AND r.status = 'ACTIVE' AND r.expires_at > :now), 0) "
                        + "FROM products p WHERE p.sku = :sku")
                .param("sku", sku)
                .param("now", SqlTime.toDb(now))
                .query(Long.class)
                .optional()
                .map(Long::intValue)
                .orElse(0);
    }

    @Override
    public <R> Optional<R> executeOnProduct(String sku, StockOperation<R> operation) {
        return executeOnProduct(sku, null, operation);
    }

    @Override
    public <R> Optional<R> executeOnProduct(String sku, String orderId, StockOperation<R> operation) {
        Objects.requireNonNull(operation, "operation");
        try {
            return tx.execute(status -> runLocked(sku, orderId, operation));
        } catch (UncategorizedSQLException e) {
            if (isLockTimeout(e)) {
                throw new CannotAcquireLockException("Timed out waiting for the lock of product " + sku, e);
            }
            throw e;
        }
    }

    private <R> Optional<R> runLocked(String sku, String orderId, StockOperation<R> operation) {
        applyLockTimeout();
        Optional<ProductRow> row = lockProduct(sku);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        ProductStock product = rehydrate(row.get(), orderId);
        OperationResult<R> result = operation.apply(product);
        persist(product, result.alerts());
        return Optional.ofNullable(result.value());
    }

    private static boolean isLockTimeout(UncategorizedSQLException e) {
        SQLException cause = e.getSQLException();
        return cause != null && LOCK_NOT_AVAILABLE.equals(cause.getSQLState());
    }

    private void applyLockTimeout() {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", lockTimeoutSetting)
                .query(String.class)
                .single();
    }

    private Optional<ProductRow> lockProduct(String sku) {
        return jdbc.sql("SELECT sku, category, on_hand, alert_armed FROM products WHERE sku = :sku FOR UPDATE")
                .param("sku", sku)
                .query((rs, rowNum) -> new ProductRow(rs.getString("sku"), parseCategory(rs.getString("category")),
                        rs.getInt("on_hand"), rs.getBoolean("alert_armed")))
                .optional();
    }

    private ProductStock rehydrate(ProductRow row, String orderId) {
        List<StockReservation> reservations = orderId == null
                ? jdbc.sql(SELECT_RESERVATION + "WHERE sku = :sku AND status = 'ACTIVE'")
                        .param("sku", row.sku())
                        .query(JdbcInventoryRepository::mapReservation)
                        .list()
                : jdbc.sql(SELECT_RESERVATION + "WHERE sku = :sku AND (status = 'ACTIVE' OR order_id = :orderId)")
                        .param("sku", row.sku())
                        .param("orderId", orderId)
                        .query(JdbcInventoryRepository::mapReservation)
                        .list();
        return ProductStock.rehydrate(row.sku(), row.category(), row.onHand(), row.alertArmed(), reservations);
    }

    private void persist(ProductStock product, List<LowStockAlert> alerts) {
        StockChanges changes = product.pullChanges();
        jdbc.sql("UPDATE products SET on_hand = :onHand, alert_armed = :armed, updated_at = now() WHERE sku = :sku")
                .param("onHand", product.onHand())
                .param("armed", product.alertArmed())
                .param("sku", product.sku())
                .update();
        if (!changes.removedOrderIds().isEmpty()) {
            jdbc.sql("DELETE FROM reservations WHERE sku = :sku AND order_id IN (:orderIds)")
                    .param("sku", product.sku())
                    .param("orderIds", List.copyOf(changes.removedOrderIds()))
                    .update();
        }
        changes.upserts().forEach(this::upsert);
        alerts.forEach(this::enqueue);
    }

    /**
     * {@code confirmed_at} es informativo (hora de la base de datos); la hora de negocio siempre viene del reloj de la
     * aplicación.
     */
    private void upsert(StockReservation reservation) {
        int affected = jdbc.sql("INSERT INTO reservations(order_id, sku, quantity, status, expires_at, confirmed_at) "
                        + "VALUES (:orderId, :sku, :quantity, :status, :expiresAt, "
                        + "CASE WHEN :status = 'CONFIRMED' THEN now() END) "
                        + "ON CONFLICT (order_id) DO UPDATE SET quantity = EXCLUDED.quantity, "
                        + "status = EXCLUDED.status, expires_at = EXCLUDED.expires_at, "
                        + "confirmed_at = COALESCE(reservations.confirmed_at, EXCLUDED.confirmed_at) "
                        + "WHERE reservations.sku = EXCLUDED.sku")
                .param("orderId", reservation.orderId())
                .param("sku", reservation.sku())
                .param("quantity", reservation.quantity())
                .param("status", reservation.status().name())
                .param("expiresAt", SqlTime.toDb(reservation.expiresAt()))
                .update();
        if (affected == 0) {
            throw new OrderIdConflictException(reservation.orderId());
        }
    }

    private void enqueue(LowStockAlert alert) {
        jdbc.sql("INSERT INTO alert_outbox(sku, available_units, occurred_at, status, next_attempt_at) "
                        + "VALUES (:sku, :available, :occurredAt, 'PENDING', :occurredAt)")
                .param("sku", alert.sku())
                .param("available", alert.availableUnits())
                .param("occurredAt", SqlTime.toDb(alert.occurredAt()))
                .update();
    }

    private static StockReservation mapReservation(ResultSet rs, int rowNum) throws SQLException {
        return new StockReservation(rs.getString("order_id"), rs.getString("sku"), rs.getInt("quantity"),
                SqlTime.fromDb(rs.getObject("expires_at", OffsetDateTime.class)),
                ReservationStatus.valueOf(rs.getString("status")));
    }

    private static ProductCategory parseCategory(String stored) {
        try {
            return ProductCategory.valueOf(stored);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown product category stored in the database: " + stored, e);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

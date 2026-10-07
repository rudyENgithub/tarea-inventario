package com.store.inventory.domain.stock;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.policy.CategoryPolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Agregado de un producto: stock físico ({@code onHand}), el latch de stock bajo y las reservas relevantes para la
 * operación en curso (todas las {@code ACTIVE} más, opcionalmente, la de la orden que se está procesando).
 *
 * <p>{@code available = onHand - sum(reservas ACTIVE no expiradas)}.
 * No es thread-safe por diseño: la exclusión mutua es responsabilidad del repositorio.
 * Toda operación valida antes de mutar, por lo que una llamada fallida deja el agregado intacto.
 */
public final class ProductStock {

    private final String sku;
    private final ProductCategory category;
    private int onHand;
    private boolean alertArmed;
    private final Map<String, StockReservation> reservations = new LinkedHashMap<>();
    private final Map<String, StockReservation> pendingUpserts = new LinkedHashMap<>();
    private final Set<String> pendingRemovals = new LinkedHashSet<>();

    private ProductStock(String sku, ProductCategory category, int onHand, boolean alertArmed) {
        this.sku = sku;
        this.category = category;
        this.onHand = onHand;
        this.alertArmed = alertArmed;
    }

    /** Un producto recién registrado: sin stock y con el latch de alerta armado. */
    public static ProductStock register(String sku, ProductCategory category) {
        requireSku(sku);
        requireCategory(category);
        return new ProductStock(sku, category, 0, true);
    }

    /** Reconstruye el agregado a partir del estado persistido. */
    public static ProductStock rehydrate(String sku, ProductCategory category, int onHand, boolean alertArmed,
            Collection<StockReservation> reservations) {
        requireSku(sku);
        requireCategory(category);
        if (onHand < 0) {
            throw new IllegalArgumentException("onHand must not be negative: " + onHand);
        }
        ProductStock product = new ProductStock(sku, category, onHand, alertArmed);
        for (StockReservation reservation : reservations) {
            if (!sku.equals(reservation.sku())) {
                throw new IllegalArgumentException(
                        "Reservation " + reservation.orderId() + " belongs to " + reservation.sku() + ", not " + sku);
            }
            if (product.reservations.putIfAbsent(reservation.orderId(), reservation) != null) {
                throw new IllegalArgumentException("Duplicate reservation for order " + reservation.orderId());
            }
        }
        return product;
    }

    public String sku() {
        return sku;
    }

    public ProductCategory category() {
        return category;
    }

    public int onHand() {
        return onHand;
    }

    public boolean alertArmed() {
        return alertArmed;
    }

    public Collection<StockReservation> reservations() {
        return Collections.unmodifiableCollection(reservations.values());
    }

    public Optional<StockReservation> reservation(String orderId) {
        return Optional.ofNullable(reservations.get(orderId));
    }

    /** Unidades que aún se pueden reservar en {@code now}. */
    public int availableAt(Instant now) {
        int held = 0;
        for (StockReservation reservation : reservations.values()) {
            if (reservation.holdsUnitsAt(now)) {
                held += reservation.quantity();
            }
        }
        int available = onHand - held;
        if (available < 0) {
            throw new IllegalStateException(
                    "Corrupted stock for " + sku + ": onHand " + onHand + " < reserved " + held);
        }
        return available;
    }

    public void addStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive for " + sku + ": " + quantity);
        }
        try {
            onHand = Math.addExact(onHand, quantity);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("stock overflow for " + sku + " adding " + quantity, e);
        }
    }

    public void armAlert() {
        alertArmed = true;
    }

    public void disarmAlert() {
        alertArmed = false;
    }

    /**
     * Reserva unidades para una orden, de forma idempotente por {@code orderId}.
     * Orden de validación: cantidad, límite por orden, stock.
     */
    public ReserveOutcome reserve(String orderId, int quantity, CategoryPolicy policy, Instant now) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (policy.category() != category) {
            throw new IllegalArgumentException(
                    "Policy for " + policy.category() + " cannot be applied to " + sku + " (" + category + ")");
        }
        StockReservation existing = reservations.get(orderId);
        if (existing != null && !(existing.status() == ReservationStatus.ACTIVE && existing.isExpiredAt(now))) {
            if (existing.quantity() != quantity) {
                throw new IllegalArgumentException("Order " + orderId + " already reserved " + existing.quantity()
                        + " units of " + sku + ", requested " + quantity);
            }
            return new ReserveOutcome(existing, false);
        }
        int limit = policy.maxUnitsPerOrder();
        if (quantity > limit) {
            throw new OrderLimitExceededException(sku, quantity, limit);
        }
        int available = availableAt(now);
        if (quantity > available) {
            throw new InsufficientStockException(sku, quantity, available);
        }
        Instant expiresAt = now.plus(policy.paymentWindow()).truncatedTo(ChronoUnit.MICROS);
        StockReservation created = new StockReservation(orderId, sku, quantity, expiresAt, ReservationStatus.ACTIVE);
        reservations.put(orderId, created);
        recordUpsert(created);
        return new ReserveOutcome(created, true);
    }

    /**
     * Convierte una reserva activa en una venta: las unidades salen de {@code onHand} y nunca regresan.
     * Confirmar una orden ya confirmada no hace nada.
     *
     * @throws IllegalStateException si la orden no tiene una reserva activa (no expirada)
     */
    public void confirm(String orderId, Instant now) {
        StockReservation reservation = reservations.get(orderId);
        if (reservation == null) {
            throw new IllegalStateException("No active reservation for order " + orderId);
        }
        if (reservation.status() == ReservationStatus.CONFIRMED) {
            return;
        }
        if (reservation.isExpiredAt(now)) {
            throw new IllegalStateException("No active reservation for order " + orderId + ": it expired at "
                    + reservation.expiresAt());
        }
        if (reservation.quantity() > onHand) {
            throw new IllegalStateException("Corrupted stock for " + sku + ": confirming " + reservation.quantity()
                    + " with onHand " + onHand);
        }
        onHand -= reservation.quantity();
        StockReservation confirmed = reservation.confirmed();
        reservations.put(orderId, confirmed);
        recordUpsert(confirmed);
    }

    /** Descarta las reservas {@code ACTIVE} expiradas. Nunca cambia {@code onHand} ni {@code availableAt(now)}. */
    public int purgeExpired(Instant now) {
        Set<String> expired = new HashSet<>();
        for (StockReservation reservation : reservations.values()) {
            if (reservation.status() == ReservationStatus.ACTIVE && reservation.isExpiredAt(now)) {
                expired.add(reservation.orderId());
            }
        }
        for (String orderId : expired) {
            reservations.remove(orderId);
            pendingUpserts.remove(orderId);
            pendingRemovals.add(orderId);
        }
        return expired.size();
    }

    /** Devuelve y limpia los cambios de reservas acumulados desde la última llamada. */
    public StockChanges pullChanges() {
        StockChanges changes = new StockChanges(new ArrayList<>(pendingUpserts.values()), pendingRemovals);
        pendingUpserts.clear();
        pendingRemovals.clear();
        return changes;
    }

    private void recordUpsert(StockReservation reservation) {
        pendingUpserts.put(reservation.orderId(), reservation);
        pendingRemovals.remove(reservation.orderId());
    }

    private static void requireSku(String sku) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
    }

    private static void requireCategory(ProductCategory category) {
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
    }
}

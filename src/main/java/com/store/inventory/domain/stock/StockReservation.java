package com.store.inventory.domain.stock;

import com.store.inventory.api.Reservation;
import java.time.Instant;

/** Una reserva de unidades para una orden. Inmutable. */
public record StockReservation(String orderId, String sku, int quantity, Instant expiresAt, ReservationStatus status) {

    public StockReservation {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    /** Expirada a la hora {@code now} cuando {@code now >= expiresAt}. */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Indica si esta reserva descuenta unidades del stock disponible en {@code now}. */
    public boolean holdsUnitsAt(Instant now) {
        return status == ReservationStatus.ACTIVE && !isExpiredAt(now);
    }

    public Reservation toContract() {
        return new Reservation(orderId, sku, quantity, expiresAt);
    }

    public StockReservation confirmed() {
        return new StockReservation(orderId, sku, quantity, expiresAt, ReservationStatus.CONFIRMED);
    }
}

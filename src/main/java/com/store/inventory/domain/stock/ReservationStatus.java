package com.store.inventory.domain.stock;

/**
 * Estados almacenados de una reserva. La expiración <em>no</em> es un estado: se deriva del reloj
 * ({@link StockReservation#isExpiredAt}).
 */
public enum ReservationStatus {
    ACTIVE,
    CONFIRMED
}

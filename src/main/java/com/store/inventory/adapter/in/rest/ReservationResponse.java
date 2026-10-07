package com.store.inventory.adapter.in.rest;

import com.store.inventory.api.Reservation;
import java.time.Instant;

public record ReservationResponse(String orderId, String sku, int quantity, Instant expiresAt) {

    static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.orderId(), reservation.sku(), reservation.quantity(), reservation.expiresAt());
    }
}

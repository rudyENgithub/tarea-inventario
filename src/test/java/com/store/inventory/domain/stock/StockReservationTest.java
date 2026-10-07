package com.store.inventory.domain.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.api.Reservation;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockReservationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static StockReservation active(Instant expiresAt) {
        return new StockReservation("O1", "A", 3, expiresAt, ReservationStatus.ACTIVE);
    }

    @Test
    void constructorValidatesEveryField() {
        assertThatThrownBy(() -> new StockReservation(null, "A", 1, T0, ReservationStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockReservation(" ", "A", 1, T0, ReservationStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockReservation("O1", "", 1, T0, ReservationStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockReservation("O1", "A", 0, T0, ReservationStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockReservation("O1", "A", 1, null, ReservationStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StockReservation("O1", "A", 1, T0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RF-E3: expired exactly at expiresAt, not expired one nanosecond before")
    void expirationBoundary() {
        var reservation = active(T0);

        assertThat(reservation.isExpiredAt(T0)).isTrue();
        assertThat(reservation.holdsUnitsAt(T0)).isFalse();
        assertThat(reservation.isExpiredAt(T0.minusNanos(1))).isFalse();
        assertThat(reservation.holdsUnitsAt(T0.minusNanos(1))).isTrue();
    }

    @Test
    void confirmedReservationsNeverHoldUnits() {
        var confirmed = active(T0.plusSeconds(60)).confirmed();

        assertThat(confirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(confirmed.holdsUnitsAt(T0)).isFalse();
    }

    @Test
    void convertsToTheContractRecord() {
        assertThat(active(T0).toContract()).isEqualTo(new Reservation("O1", "A", 3, T0));
    }
}

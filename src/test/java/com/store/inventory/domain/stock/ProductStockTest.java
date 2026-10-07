package com.store.inventory.domain.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.api.ProductCategory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductStockTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static StockReservation active(String orderId, int qty, Instant expiresAt) {
        return new StockReservation(orderId, "A", qty, expiresAt, ReservationStatus.ACTIVE);
    }

    @Test
    void registeredProductStartsEmptyAndArmed() {
        var product = ProductStock.register("A", ProductCategory.STANDARD);

        assertThat(product.onHand()).isZero();
        assertThat(product.availableAt(T0)).isZero();
        assertThat(product.alertArmed()).isTrue();
        assertThat(product.reservations()).isEmpty();
        assertThat(product.pullChanges().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("RF-S2: addStock(10) makes 10 units available and accumulates")
    void addStockAccumulates() {
        var product = ProductStock.register("A", ProductCategory.STANDARD);

        product.addStock(10);
        assertThat(product.availableAt(T0)).isEqualTo(10);
        product.addStock(5);
        assertThat(product.availableAt(T0)).isEqualTo(15);
    }

    @Test
    @DisplayName("RF-S3: addStock rejects non positive quantities and keeps the state")
    void addStockRejectsNonPositive() {
        var product = ProductStock.register("A", ProductCategory.STANDARD);
        product.addStock(4);

        assertThatThrownBy(() -> product.addStock(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> product.addStock(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(product.onHand()).isEqualTo(4);
    }

    @Test
    void addStockProtectsFromOverflow() {
        var product = ProductStock.rehydrate("A", ProductCategory.STANDARD, Integer.MAX_VALUE, true, List.of());

        assertThatThrownBy(() -> product.addStock(1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(product.onHand()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("RF-E2: available = onHand - active non expired reservations (boundary at expiresAt)")
    void availabilityIgnoresExpiredReservations() {
        var product = ProductStock.rehydrate("A", ProductCategory.STANDARD, 10, true,
                List.of(active("O1", 3, T0.plusSeconds(5)), active("O2", 2, T0.plusSeconds(1))));

        assertThat(product.availableAt(T0)).isEqualTo(5);
        assertThat(product.availableAt(T0.plusSeconds(1))).isEqualTo(7);
        assertThat(product.availableAt(T0.plusSeconds(5))).isEqualTo(10);
    }

    @Test
    void confirmedReservationsDoNotReduceAvailability() {
        var confirmed = new StockReservation("O1", "A", 2, T0.plusSeconds(60), ReservationStatus.CONFIRMED);
        var product = ProductStock.rehydrate("A", ProductCategory.STANDARD, 3, true, List.of(confirmed));

        assertThat(product.availableAt(T0)).isEqualTo(3);
    }

    @Test
    void rehydrationRejectsInconsistentState() {
        var foreign = new StockReservation("O1", "B", 1, T0, ReservationStatus.ACTIVE);

        assertThatThrownBy(() -> ProductStock.rehydrate("A", ProductCategory.STANDARD, 5, true, List.of(foreign)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductStock.rehydrate("A", ProductCategory.STANDARD, 5, true,
                List.of(active("O1", 1, T0), active("O1", 2, T0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductStock.rehydrate("A", ProductCategory.STANDARD, -1, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void corruptedReservationsAreDetectedWhenComputingAvailability() {
        var product = ProductStock.rehydrate("A", ProductCategory.STANDARD, 2, true,
                List.of(active("O1", 5, T0.plusSeconds(60))));

        assertThatThrownBy(() -> product.availableAt(T0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void alertLatchCanBeToggled() {
        var product = ProductStock.register("A", ProductCategory.STANDARD);

        product.disarmAlert();
        assertThat(product.alertArmed()).isFalse();
        product.armAlert();
        assertThat(product.alertArmed()).isTrue();
    }

    @Test
    void availabilityEqualsOnHandMinusActiveReservationsForRandomStates() {
        var random = new Random(42);
        for (int i = 0; i < 1_000; i++) {
            var reservations = new ArrayList<StockReservation>();
            int held = 0;
            int count = random.nextInt(6);
            for (int r = 0; r < count; r++) {
                int qty = 1 + random.nextInt(5);
                Instant expiresAt = T0.plusSeconds(random.nextInt(20) - 10);
                reservations.add(active("O" + r, qty, expiresAt));
                if (expiresAt.isAfter(T0)) {
                    held += qty;
                }
            }
            int onHand = held + random.nextInt(10);
            var product = ProductStock.rehydrate("A", ProductCategory.STANDARD, onHand, true, reservations);

            assertThat(product.availableAt(T0)).isGreaterThanOrEqualTo(0).isEqualTo(onHand - held);
        }
    }
}

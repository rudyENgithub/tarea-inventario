package com.store.inventory.domain.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.policy.CategoryPolicy;
import com.store.inventory.domain.policy.StandardCategoryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductStockConfirmTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final CategoryPolicy STANDARD = new StandardCategoryPolicy();

    private static ProductStock stocked(int units) {
        var product = ProductStock.register("A", ProductCategory.STANDARD);
        product.addStock(units);
        return product;
    }

    @Test
    @DisplayName("RF-C1: confirmed units stay sold and never return to the stock")
    void confirmedUnitsStaySold() {
        var product = stocked(5);
        product.reserve("O1", 2, STANDARD, T0);

        product.confirm("O1", T0);

        assertThat(product.availableAt(T0)).isEqualTo(3);
        assertThat(product.onHand()).isEqualTo(3);
        assertThat(product.availableAt(T0.plus(Duration.ofDays(10)))).isEqualTo(3);
    }

    @Test
    @DisplayName("AD-7: accounting table onHand / available per event")
    void accountingTable() {
        var product = stocked(10);
        assertThat(new int[] {product.onHand(), product.availableAt(T0)}).containsExactly(10, 10);

        product.reserve("O1", 4, STANDARD, T0);
        assertThat(new int[] {product.onHand(), product.availableAt(T0)}).containsExactly(10, 6);

        assertThat(product.availableAt(T0.plus(Duration.ofMinutes(15)))).isEqualTo(10);

        product.confirm("O1", T0);
        assertThat(new int[] {product.onHand(), product.availableAt(T0)}).containsExactly(6, 6);
    }

    @Test
    @DisplayName("RF-C2: unknown orders cannot be confirmed")
    void unknownOrderIsRejected() {
        var product = stocked(5);

        assertThatThrownBy(() -> product.confirm("unknown", T0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("RF-C2: confirming exactly at expiresAt fails, one nanosecond before succeeds")
    void expiredReservationCannotBeConfirmed() {
        var product = stocked(5);
        var reservation = product.reserve("O1", 2, STANDARD, T0).reservation();

        assertThatThrownBy(() -> product.confirm("O1", reservation.expiresAt()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(product.onHand()).isEqualTo(5);

        product.confirm("O1", reservation.expiresAt().minusNanos(1));
        assertThat(product.onHand()).isEqualTo(3);
    }

    @Test
    @DisplayName("RF-C3: confirming twice is a no-op")
    void confirmIsIdempotent() {
        var product = stocked(5);
        product.reserve("O1", 2, STANDARD, T0);
        product.confirm("O1", T0);
        product.pullChanges();

        product.confirm("O1", T0.plusSeconds(1));

        assertThat(product.onHand()).isEqualTo(3);
        assertThat(product.availableAt(T0)).isEqualTo(3);
        assertThat(product.pullChanges().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("RF-E1 / RF-E3: units return once the window passes, without any mutation")
    void expirationIsLazyAndDeterministic() {
        var product = stocked(10);
        product.reserve("O1", 4, STANDARD, T0);

        assertThat(product.availableAt(T0)).isEqualTo(6);
        assertThat(product.availableAt(T0.plus(Duration.ofMinutes(15)))).isEqualTo(10);
        assertThat(product.availableAt(T0)).isEqualTo(6);
    }

    @Test
    @DisplayName("RF-E2: available counts only the non expired reservations")
    void onlyLiveReservationsCount() {
        var product = stocked(10);
        product.reserve("O1", 3, STANDARD, T0);
        product.reserve("O2", 2, STANDARD, T0.plus(Duration.ofMinutes(10)));

        assertThat(product.availableAt(T0.plus(Duration.ofMinutes(16)))).isEqualTo(8);
    }

    @Test
    void purgeRemovesOnlyExpiredActiveReservations() {
        var expired = new StockReservation("OLD", "A", 2, T0.minusSeconds(1), ReservationStatus.ACTIVE);
        var live = new StockReservation("LIVE", "A", 3, T0.plusSeconds(60), ReservationStatus.ACTIVE);
        var confirmed = new StockReservation("SOLD", "A", 1, T0.minusSeconds(100), ReservationStatus.CONFIRMED);
        var product = ProductStock.rehydrate("A", ProductCategory.STANDARD, 10, true, List.of(expired, live, confirmed));
        int before = product.availableAt(T0);

        int purged = product.purgeExpired(T0);

        assertThat(purged).isEqualTo(1);
        assertThat(product.reservation("OLD")).isEmpty();
        assertThat(product.reservation("LIVE")).isPresent();
        assertThat(product.reservation("SOLD")).isPresent();
        assertThat(product.availableAt(T0)).isEqualTo(before);
        assertThat(product.onHand()).isEqualTo(10);
        assertThat(product.pullChanges().removedOrderIds()).containsExactly("OLD");
    }

    @Test
    void purgeNeverChangesAvailabilityForRandomStates() {
        var random = new Random(7);
        for (int i = 0; i < 1_000; i++) {
            var reservations = new ArrayList<StockReservation>();
            int held = 0;
            for (int r = 0; r < random.nextInt(6); r++) {
                int qty = 1 + random.nextInt(4);
                Instant expiresAt = T0.plusSeconds(random.nextInt(20) - 10);
                var status = random.nextInt(4) == 0 ? ReservationStatus.CONFIRMED : ReservationStatus.ACTIVE;
                reservations.add(new StockReservation("O" + r, "A", qty, expiresAt, status));
                if (status == ReservationStatus.ACTIVE && expiresAt.isAfter(T0)) {
                    held += qty;
                }
            }
            var product = ProductStock.rehydrate("A", ProductCategory.STANDARD, held + random.nextInt(8), true,
                    reservations);
            int before = product.availableAt(T0);

            product.purgeExpired(T0);

            assertThat(product.availableAt(T0)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("Assumption 8: reserving again after expiration creates a new reservation without double counting")
    void reserveAfterExpirationCreatesNewReservation() {
        var product = stocked(5);
        product.reserve("O1", 5, STANDARD, T0);
        Instant later = T0.plus(Duration.ofMinutes(20));

        product.purgeExpired(later);
        var outcome = product.reserve("O1", 5, STANDARD, later);

        assertThat(outcome.created()).isTrue();
        assertThat(product.availableAt(later)).isZero();
        var changes = product.pullChanges();
        assertThat(changes.upserts()).hasSize(1);
        assertThat(changes.removedOrderIds()).doesNotContain("O1");
    }

    @Test
    void reserveConfirmReserveReplaysTheConfirmedReservation() {
        var product = stocked(5);
        var first = product.reserve("O1", 2, STANDARD, T0).reservation();
        product.confirm("O1", T0);

        var replay = product.reserve("O1", 2, STANDARD, T0.plusSeconds(5));

        assertThat(replay.created()).isFalse();
        assertThat(replay.reservation().orderId()).isEqualTo(first.orderId());
        assertThat(replay.reservation().status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(product.availableAt(T0)).isEqualTo(3);
    }

    @Test
    @DisplayName("RF-E3: the same sequence with the same instants yields identical states")
    void sequencesAreDeterministic() {
        var a = replaySequence();
        var b = replaySequence();

        assertThat(a).isEqualTo(b);
    }

    private static List<Integer> replaySequence() {
        var product = stocked(10);
        var observed = new ArrayList<Integer>();
        product.reserve("O1", 3, STANDARD, T0);
        observed.add(product.availableAt(T0));
        product.reserve("O2", 2, STANDARD, T0.plusSeconds(60));
        observed.add(product.availableAt(T0.plusSeconds(60)));
        product.confirm("O1", T0.plusSeconds(61));
        observed.add(product.availableAt(T0.plus(Duration.ofMinutes(16))));
        observed.add(product.onHand());
        return observed;
    }
}

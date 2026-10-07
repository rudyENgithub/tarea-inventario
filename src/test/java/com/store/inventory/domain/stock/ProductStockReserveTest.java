package com.store.inventory.domain.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.policy.CategoryPolicy;
import com.store.inventory.domain.policy.FlashSaleCategoryPolicy;
import com.store.inventory.domain.policy.PreOrderCategoryPolicy;
import com.store.inventory.domain.policy.StandardCategoryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductStockReserveTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final CategoryPolicy STANDARD = new StandardCategoryPolicy();
    private static final CategoryPolicy FLASH = new FlashSaleCategoryPolicy();

    private static ProductStock stocked(ProductCategory category, int units) {
        var product = ProductStock.register("A", category);
        product.addStock(units);
        return product;
    }

    @Test
    @DisplayName("RF-R1: reserving discounts availability immediately")
    void reservingReducesAvailability() {
        var product = stocked(ProductCategory.STANDARD, 10);

        var outcome = product.reserve("O1", 3, STANDARD, T0);

        assertThat(outcome.created()).isTrue();
        assertThat(product.availableAt(T0)).isEqualTo(7);
        assertThat(product.onHand()).isEqualTo(10);
    }

    @Test
    @DisplayName("RF-R2: cannot reserve more than available")
    void cannotReserveMoreThanAvailable() {
        var product = stocked(ProductCategory.STANDARD, 2);

        assertThatThrownBy(() -> product.reserve("O1", 3, STANDARD, T0))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("requested 3, available 2");
        assertThat(product.availableAt(T0)).isEqualTo(2);
        assertThat(product.reservations()).isEmpty();
        assertThat(product.pullChanges().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("RF-R3: non positive quantities are rejected")
    void nonPositiveQuantityIsRejected() {
        var product = stocked(ProductCategory.STANDARD, 10);

        assertThatThrownBy(() -> product.reserve("O1", 0, STANDARD, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> product.reserve("O1", -5, STANDARD, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(product.availableAt(T0)).isEqualTo(10);
    }

    @Test
    @DisplayName("RF-R4: replaying the same order returns the same reservation without discounting twice")
    void replayIsIdempotent() {
        var product = stocked(ProductCategory.STANDARD, 10);

        var first = product.reserve("O1", 3, STANDARD, T0);
        product.pullChanges();
        var second = product.reserve("O1", 3, STANDARD, T0.plusSeconds(30));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.reservation()).isEqualTo(first.reservation());
        assertThat(product.availableAt(T0)).isEqualTo(7);
        assertThat(product.pullChanges().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("RF-R5: FLASH_SALE allows at most 2 units per order")
    void orderLimitIsEnforcedByPolicy() {
        var flash = stocked(ProductCategory.FLASH_SALE, 10);

        assertThatThrownBy(() -> flash.reserve("O1", 3, FLASH, T0)).isInstanceOf(OrderLimitExceededException.class);
        assertThat(flash.reserve("O2", 2, FLASH, T0).created()).isTrue();

        var standard = stocked(ProductCategory.STANDARD, 1_000);
        assertThat(standard.reserve("O3", 1_000, STANDARD, T0).created()).isTrue();
    }

    @Test
    @DisplayName("RF-R5: the order limit is checked before the stock")
    void limitTakesPrecedenceOverStock() {
        var flash = stocked(ProductCategory.FLASH_SALE, 1);

        assertThatThrownBy(() -> flash.reserve("O1", 3, FLASH, T0)).isInstanceOf(OrderLimitExceededException.class);
    }

    @Test
    @DisplayName("RF-R6: expiresAt = now + payment window of the category, truncated to micros")
    void expirationUsesThePaymentWindow() {
        var standard = stocked(ProductCategory.STANDARD, 5);
        var preOrder = stocked(ProductCategory.PRE_ORDER, 5);
        var flash = stocked(ProductCategory.FLASH_SALE, 5);

        assertThat(standard.reserve("O1", 1, STANDARD, T0).reservation().expiresAt())
                .isEqualTo(T0.plus(Duration.ofMinutes(15)));
        assertThat(preOrder.reserve("O2", 1, new PreOrderCategoryPolicy(), T0).reservation().expiresAt())
                .isEqualTo(T0.plus(Duration.ofHours(24)));
        assertThat(flash.reserve("O3", 1, FLASH, T0).reservation().expiresAt())
                .isEqualTo(T0.plus(Duration.ofMinutes(5)));

        Instant withNanos = T0.plusNanos(123_456_789);
        var truncated = stocked(ProductCategory.STANDARD, 5).reserve("O4", 1, STANDARD, withNanos);
        assertThat(truncated.reservation().expiresAt().getNano() % 1_000).isZero();
        assertThat(truncated.reservation().expiresAt()).isEqualTo(T0.plusNanos(123_456_000).plus(Duration.ofMinutes(15)));
    }

    @Test
    void reusingAnOrderIdWithAnotherQuantityIsRejected() {
        var product = stocked(ProductCategory.STANDARD, 10);
        product.reserve("O1", 3, STANDARD, T0);
        product.pullChanges();

        assertThatThrownBy(() -> product.reserve("O1", 4, STANDARD, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(product.availableAt(T0)).isEqualTo(7);
        assertThat(product.pullChanges().isEmpty()).isTrue();
    }

    @Test
    void confirmedOrdersReplayOrRejectDifferentQuantity() {
        var confirmed = new StockReservation("O1", "A", 2, T0.plusSeconds(60), ReservationStatus.CONFIRMED);
        var product = ProductStock.rehydrate("A", ProductCategory.STANDARD, 8, true, List.of(confirmed));

        var replay = product.reserve("O1", 2, STANDARD, T0);

        assertThat(replay.created()).isFalse();
        assertThat(replay.reservation()).isEqualTo(confirmed);
        assertThatThrownBy(() -> product.reserve("O1", 3, STANDARD, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(product.availableAt(T0)).isEqualTo(8);
    }

    @Test
    @DisplayName("Assumption 8: an expired order id is treated as a brand new reservation")
    void expiredReservationIsReplacedByANewOne() {
        var expired = new StockReservation("O1", "A", 4, T0.minusSeconds(1), ReservationStatus.ACTIVE);
        var product = ProductStock.rehydrate("A", ProductCategory.STANDARD, 10, true, List.of(expired));

        var outcome = product.reserve("O1", 4, STANDARD, T0);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.reservation().expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
        assertThat(product.availableAt(T0)).isEqualTo(6);
        var changes = product.pullChanges();
        assertThat(changes.upserts()).containsExactly(outcome.reservation());
        assertThat(changes.removedOrderIds()).doesNotContain("O1");
    }

    @Test
    void distinctOrdersAccumulateUntilStockIsExhausted() {
        var product = stocked(ProductCategory.STANDARD, 3);

        product.reserve("O1", 1, STANDARD, T0);
        product.reserve("O2", 1, STANDARD, T0);
        product.reserve("O3", 1, STANDARD, T0);

        assertThatThrownBy(() -> product.reserve("O4", 1, STANDARD, T0))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(product.availableAt(T0)).isZero();
    }

    @Test
    void wrongCategoryPolicyIsRejected() {
        var product = stocked(ProductCategory.STANDARD, 3);

        assertThatThrownBy(() -> product.reserve("O1", 1, FLASH, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(product.availableAt(T0)).isEqualTo(3);
    }

    @Test
    void changesAreRecordedOnceAndDrained() {
        var product = stocked(ProductCategory.STANDARD, 3);

        var outcome = product.reserve("O1", 1, STANDARD, T0);

        assertThat(product.pullChanges().upserts()).containsExactly(outcome.reservation());
        assertThat(product.pullChanges().isEmpty()).isTrue();
    }
}

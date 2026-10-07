package com.store.inventory.domain.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.policy.CategoryPolicy;
import com.store.inventory.domain.policy.StandardCategoryPolicy;
import com.store.inventory.domain.stock.ProductStock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LowStockDetectorTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final CategoryPolicy STANDARD = new StandardCategoryPolicy();

    private final LowStockDetector detector = new LowStockDetector(LowStockThreshold.DEFAULT);
    private int orders;

    private static ProductStock stocked(int units) {
        var product = ProductStock.register("A", ProductCategory.STANDARD);
        product.addStock(units);
        return product;
    }

    private Optional<LowStockAlert> reserve(ProductStock product, int qty, Instant now) {
        var outcome = product.reserve("O" + (++orders), qty, STANDARD, now);
        return outcome.created() ? detector.afterNewReservation(product, now) : Optional.empty();
    }

    @Test
    @DisplayName("RF-A1: exactly one alert when availability falls to the threshold")
    void alertsOnceWhenCrossingTheThreshold() {
        var product = stocked(10);

        for (int i = 0; i < 4; i++) {
            assertThat(reserve(product, 1, T0)).isEmpty();
        }
        var alert = reserve(product, 1, T0);

        assertThat(alert).isPresent();
        assertThat(alert.get().availableUnits()).isEqualTo(5);
        assertThat(alert.get().sku()).isEqualTo("A");
        assertThat(alert.get().occurredAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("RF-A1: a reservation that jumps over the threshold reports the real availability")
    void alertReportsActualAvailability() {
        var product = stocked(11);

        var alert = reserve(product, 9, T0);

        assertThat(alert).isPresent();
        assertThat(alert.get().availableUnits()).isEqualTo(2);
    }

    @Test
    @DisplayName("RF-A2: no repeated alert while the product is not restocked")
    void doesNotRepeat() {
        var product = stocked(10);
        reserve(product, 5, T0);

        assertThat(reserve(product, 1, T0)).isEmpty();
        assertThat(reserve(product, 1, T0)).isEmpty();
        assertThat(reserve(product, 1, T0)).isEmpty();
        assertThat(product.alertArmed()).isFalse();
    }

    @Test
    @DisplayName("RF-A3: restocking above the threshold re-arms the alert")
    void restockRearms() {
        var product = stocked(10);
        reserve(product, 5, T0);

        product.addStock(3);
        detector.afterRestock(product, T0);
        assertThat(product.availableAt(T0)).isEqualTo(8);
        assertThat(product.alertArmed()).isTrue();

        var alert = reserve(product, 3, T0);
        assertThat(alert).isPresent();
        assertThat(alert.get().availableUnits()).isEqualTo(5);
    }

    @Test
    @DisplayName("RF-A3: restocking up to exactly the threshold does not re-arm")
    void restockToThresholdDoesNotRearm() {
        var product = stocked(10);
        reserve(product, 9, T0);
        assertThat(product.alertArmed()).isFalse();

        product.addStock(4);
        detector.afterRestock(product, T0);

        assertThat(product.availableAt(T0)).isEqualTo(5);
        assertThat(product.alertArmed()).isFalse();
    }

    @Test
    @DisplayName("D4: a product that never exceeded the threshold alerts on its first reservation")
    void lowStockProductAlertsOnFirstReservation() {
        var product = stocked(3);

        var alert = reserve(product, 1, T0);

        assertThat(alert).isPresent();
        assertThat(alert.get().availableUnits()).isEqualTo(2);
    }

    @Test
    @DisplayName("Assumption 6: expiration of reservations does not re-arm the alert")
    void expirationDoesNotRearm() {
        var product = stocked(10);
        reserve(product, 6, T0);
        assertThat(product.alertArmed()).isFalse();

        Instant later = T0.plus(Duration.ofMinutes(20));
        assertThat(product.availableAt(later)).isEqualTo(10);

        assertThat(reserve(product, 6, later)).isEmpty();
        assertThat(product.alertArmed()).isFalse();
    }

    @Test
    void thresholdIsConfigurableAndBoundaryIsInclusive() {
        var zero = new LowStockDetector(new LowStockThreshold(0));
        var product = stocked(3);
        product.reserve("X1", 2, STANDARD, T0);
        assertThat(zero.afterNewReservation(product, T0)).isEmpty();
        product.reserve("X2", 1, STANDARD, T0);
        assertThat(zero.afterNewReservation(product, T0)).isPresent();

        assertThatThrownBy(() -> new LowStockThreshold(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(LowStockThreshold.DEFAULT.units()).isEqualTo(5);
        assertThat(LowStockThreshold.DEFAULT.isLow(6)).isFalse();
        assertThat(LowStockThreshold.DEFAULT.isLow(5)).isTrue();
    }

    @Test
    void alertValidatesItsFields() {
        assertThatThrownBy(() -> new LowStockAlert(" ", 1, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LowStockAlert("A", -1, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LowStockAlert("A", 1, null)).isInstanceOf(IllegalArgumentException.class);
    }
}

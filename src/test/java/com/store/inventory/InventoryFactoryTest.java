package com.store.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.testsupport.MutableClock;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InventoryFactoryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> Inventory.create(null, (sku, available) -> { }))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Inventory.create(Clock.systemUTC(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("C-2 C-3: Inventory.create(Clock, StockAlertListener) returns an independent InventoryService")
    void everyCallReturnsAnIndependentService() {
        InventoryService first = Inventory.create(new MutableClock(T0), (sku, available) -> { });
        InventoryService second = Inventory.create(new MutableClock(T0), (sku, available) -> { });

        first.registerProduct("A", ProductCategory.STANDARD);
        first.addStock("A", 5);

        assertThat(first.available("A")).isEqualTo(5);
        assertThat(second.available("A")).isZero();
        assertThatThrownBy(() -> second.addStock("A", 1)).isInstanceOf(IllegalArgumentException.class);
    }
}

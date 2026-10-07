package com.store.inventory.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CategoryPolicyTest {

    @Test
    @DisplayName("STANDARD: 15 minutes, unlimited")
    void standard() {
        var policy = new StandardCategoryPolicy();
        assertThat(policy.category()).isEqualTo(ProductCategory.STANDARD);
        assertThat(policy.paymentWindow()).isEqualTo(Duration.ofMinutes(15));
        assertThat(policy.maxUnitsPerOrder()).isEqualTo(CategoryPolicy.UNLIMITED);
    }

    @Test
    @DisplayName("PRE_ORDER: 24 hours, unlimited")
    void preOrder() {
        var policy = new PreOrderCategoryPolicy();
        assertThat(policy.category()).isEqualTo(ProductCategory.PRE_ORDER);
        assertThat(policy.paymentWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(policy.maxUnitsPerOrder()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("FLASH_SALE: 5 minutes, 2 units per order")
    void flashSale() {
        var policy = new FlashSaleCategoryPolicy();
        assertThat(policy.category()).isEqualTo(ProductCategory.FLASH_SALE);
        assertThat(policy.paymentWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.maxUnitsPerOrder()).isEqualTo(2);
    }
}

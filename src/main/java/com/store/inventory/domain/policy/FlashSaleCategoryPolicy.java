package com.store.inventory.domain.policy;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;

/** {@link ProductCategory#FLASH_SALE}: 5 minutos para pagar, máximo 2 unidades por orden. */
public final class FlashSaleCategoryPolicy implements CategoryPolicy {

    private static final Duration PAYMENT_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_UNITS = 2;

    @Override
    public ProductCategory category() {
        return ProductCategory.FLASH_SALE;
    }

    @Override
    public Duration paymentWindow() {
        return PAYMENT_WINDOW;
    }

    @Override
    public int maxUnitsPerOrder() {
        return MAX_UNITS;
    }
}

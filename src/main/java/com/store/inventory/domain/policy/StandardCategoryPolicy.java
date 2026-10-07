package com.store.inventory.domain.policy;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;

/** {@link ProductCategory#STANDARD}: 15 minutos para pagar, sin límite por orden. */
public final class StandardCategoryPolicy implements CategoryPolicy {

    private static final Duration PAYMENT_WINDOW = Duration.ofMinutes(15);

    @Override
    public ProductCategory category() {
        return ProductCategory.STANDARD;
    }

    @Override
    public Duration paymentWindow() {
        return PAYMENT_WINDOW;
    }

    @Override
    public int maxUnitsPerOrder() {
        return UNLIMITED;
    }
}

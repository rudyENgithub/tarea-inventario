package com.store.inventory.domain.policy;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;

/** {@link ProductCategory#PRE_ORDER}: 24 horas para pagar (transferencia bancaria), sin límite por orden. */
public final class PreOrderCategoryPolicy implements CategoryPolicy {

    private static final Duration PAYMENT_WINDOW = Duration.ofHours(24);

    @Override
    public ProductCategory category() {
        return ProductCategory.PRE_ORDER;
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

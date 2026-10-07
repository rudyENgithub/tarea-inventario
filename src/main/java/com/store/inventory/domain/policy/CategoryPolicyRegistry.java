package com.store.inventory.domain.policy;

import com.store.inventory.api.ProductCategory;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resuelve el {@link CategoryPolicy} de cada {@link ProductCategory}. Falla rápido al construirse cuando una
 * categoría no tiene política, de modo que un nuevo valor del enum no pase inadvertido.
 */
public final class CategoryPolicyRegistry {

    private final Map<ProductCategory, CategoryPolicy> policies;

    private CategoryPolicyRegistry(Map<ProductCategory, CategoryPolicy> policies) {
        this.policies = policies;
    }

    /**
     * @throws IllegalArgumentException si una política es nula, inválida o está duplicada para una categoría
     * @throws IllegalStateException si alguna categoría no tiene política
     */
    public static CategoryPolicyRegistry of(Collection<? extends CategoryPolicy> policies) {
        if (policies == null) {
            throw new IllegalArgumentException("policies must not be null");
        }
        Map<ProductCategory, CategoryPolicy> byCategory = new EnumMap<>(ProductCategory.class);
        for (CategoryPolicy policy : policies) {
            if (policy == null) {
                throw new IllegalArgumentException("policy must not be null");
            }
            validate(policy);
            if (byCategory.putIfAbsent(policy.category(), policy) != null) {
                throw new IllegalArgumentException("Duplicate CategoryPolicy for " + policy.category());
            }
        }
        for (ProductCategory category : ProductCategory.values()) {
            if (!byCategory.containsKey(category)) {
                throw new IllegalStateException("No CategoryPolicy registered for " + category);
            }
        }
        return new CategoryPolicyRegistry(byCategory);
    }

    /** @throws NullPointerException si {@code category} es nula */
    public CategoryPolicy forCategory(ProductCategory category) {
        Objects.requireNonNull(category, "category");
        return policies.get(category);
    }

    private static void validate(CategoryPolicy policy) {
        ProductCategory category = Objects.requireNonNull(policy.category(), "policy category");
        if (policy.paymentWindow() == null || policy.paymentWindow().isZero() || policy.paymentWindow().isNegative()) {
            throw new IllegalArgumentException("paymentWindow must be positive for " + category);
        }
        if (policy.maxUnitsPerOrder() < 1) {
            throw new IllegalArgumentException("maxUnitsPerOrder must be >= 1 for " + category);
        }
    }
}

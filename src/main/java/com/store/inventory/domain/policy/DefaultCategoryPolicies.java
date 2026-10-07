package com.store.inventory.domain.policy;

import java.util.List;

/**
 * El único lugar donde se enumeran las políticas de categoría.
 * Para agregar una categoría: crea su {@link CategoryPolicy} y añádela a {@link #all()}.
 */
public final class DefaultCategoryPolicies {

    private DefaultCategoryPolicies() {
    }

    public static List<CategoryPolicy> all() {
        return List.of(
                new StandardCategoryPolicy(),
                new PreOrderCategoryPolicy(),
                new FlashSaleCategoryPolicy());
    }
}

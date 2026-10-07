package com.store.inventory.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CategoryPolicyRegistryTest {

    private record TestPolicy(ProductCategory category, Duration paymentWindow, int maxUnitsPerOrder)
            implements CategoryPolicy {
    }

    @Test
    @DisplayName("AD-2: resolves a policy for every category")
    void resolvesEveryCategory() {
        var registry = CategoryPolicyRegistry.of(DefaultCategoryPolicies.all());

        for (ProductCategory category : ProductCategory.values()) {
            assertThat(registry.forCategory(category).category()).isEqualTo(category);
        }
    }

    @Test
    @DisplayName("AD-2: fails fast naming the missing category")
    void failsFastWhenCategoryIsMissing() {
        assertThatThrownBy(() -> CategoryPolicyRegistry.of(List.of(new StandardCategoryPolicy())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No CategoryPolicy registered for")
                .satisfies(e -> assertThat(e.getMessage()).containsAnyOf("PRE_ORDER", "FLASH_SALE"));
    }

    @Test
    @DisplayName("AD-2: two policies for the same category are rejected (extension goes through registration)")
    void duplicatePolicyIsRejected() {
        var all = new ArrayList<CategoryPolicy>(DefaultCategoryPolicies.all());
        all.add(new TestPolicy(ProductCategory.STANDARD, Duration.ofMinutes(1), 1));

        assertThatThrownBy(() -> CategoryPolicyRegistry.of(all))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STANDARD");
    }

    @Test
    void invalidPoliciesAreRejected() {
        assertThatThrownBy(() -> CategoryPolicyRegistry.of(List.of(
                new TestPolicy(ProductCategory.STANDARD, Duration.ZERO, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CategoryPolicyRegistry.of(List.of(
                new TestPolicy(ProductCategory.STANDARD, Duration.ofMinutes(-1), 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CategoryPolicyRegistry.of(List.of(
                new TestPolicy(ProductCategory.STANDARD, Duration.ofMinutes(1), 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CategoryPolicyRegistry.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registryIsImmutableAfterConstruction() {
        var source = new ArrayList<CategoryPolicy>(DefaultCategoryPolicies.all());
        var registry = CategoryPolicyRegistry.of(source);
        var before = registry.forCategory(ProductCategory.STANDARD);

        source.clear();

        assertThat(registry.forCategory(ProductCategory.STANDARD)).isSameAs(before);
    }

    @Test
    void nullCategoryIsRejected() {
        var registry = CategoryPolicyRegistry.of(DefaultCategoryPolicies.all());
        assertThatThrownBy(() -> registry.forCategory(null)).isInstanceOf(NullPointerException.class);
    }
}

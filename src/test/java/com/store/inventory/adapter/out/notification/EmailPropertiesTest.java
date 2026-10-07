package com.store.inventory.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class EmailPropertiesTest {

    @Test
    void disabledSkipsValidation() {
        EmailProperties properties = new EmailProperties(false, null, List.of(), "[Inventario]");
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.to()).isEmpty();
    }

    @Test
    void enabledWithoutToFailsNamingTheProperty() {
        assertThatThrownBy(() -> new EmailProperties(true, "from@store.local", List.of(), "[Inventario]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory.alerts.email.to");
    }

    @Test
    void enabledWithoutFromFailsNamingTheProperty() {
        assertThatThrownBy(() -> new EmailProperties(true, null, List.of("a@store.local"), "[Inventario]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory.alerts.email.from");
    }

    @Test
    void invalidEmailIsRejected() {
        assertThatThrownBy(() -> new EmailProperties(true, "not-an-email", List.of("a@store.local"), "[Inventario]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory.alerts.email.from");
    }
}

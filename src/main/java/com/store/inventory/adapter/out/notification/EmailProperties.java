package com.store.inventory.adapter.out.notification;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del canal de email. La validación solo se ejecuta cuando el canal está habilitado, de modo que un
 * despliegue con el canal deshabilitado no necesita destinatarios configurados.
 */
@ConfigurationProperties("inventory.alerts.email")
public record EmailProperties(
        @DefaultValue("false") boolean enabled,
        String from,
        @DefaultValue List<String> to,
        @DefaultValue("[Inventario]") String subjectPrefix) {

    public EmailProperties {
        to = to == null ? List.of() : List.copyOf(to);
        if (subjectPrefix == null || subjectPrefix.isBlank()) {
            subjectPrefix = "[Inventario]";
        }
        if (enabled) {
            requireAddress(from, "inventory.alerts.email.from");
            if (to.isEmpty()) {
                throw new IllegalArgumentException(
                        "inventory.alerts.email.to must contain at least one address when enabled");
            }
            to.forEach(address -> requireAddress(address, "inventory.alerts.email.to"));
        }
    }

    private static void requireAddress(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must be a valid email when enabled");
        }
        try {
            new InternetAddress(value, true);
        } catch (AddressException e) {
            throw new IllegalArgumentException(property + " is not a valid email: " + value, e);
        }
    }
}

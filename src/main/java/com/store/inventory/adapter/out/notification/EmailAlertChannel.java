package com.store.inventory.adapter.out.notification;

import com.store.inventory.application.port.out.AlertChannel;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Entrega una alerta de stock bajo por email. Los fallos se propagan para que el despachador pueda reintentar. */
public final class EmailAlertChannel implements AlertChannel {

    private static final Logger LOG = LoggerFactory.getLogger(EmailAlertChannel.class);

    private final JavaMailSender sender;
    private final EmailProperties properties;

    public EmailAlertChannel(JavaMailSender sender, EmailProperties properties) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(properties.to().toArray(String[]::new));
        message.setSubject(properties.subjectPrefix() + " Bajo stock: " + sku + " (" + availableUnits + " disp.)");
        message.setText("SKU: " + sku + System.lineSeparator()
                + "Unidades disponibles: " + availableUnits + System.lineSeparator()
                + "Reabastecer lo antes posible.");
        LOG.info("low-stock mail sent sku={} to={}", sku, mask(properties.to().getFirst()));
        sender.send(message);
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}

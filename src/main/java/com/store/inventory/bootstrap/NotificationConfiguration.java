package com.store.inventory.bootstrap;

import com.store.inventory.adapter.out.notification.EmailAlertChannel;
import com.store.inventory.adapter.out.notification.EmailProperties;
import com.store.inventory.application.port.out.AlertChannel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Canales de notificación opcionales. Agregar SMS/push es otra clase que implemente {@link AlertChannel} más un
 * {@code @Bean} aquí; {@link InventoryConfiguration} ya distribuye las alertas a {@code List<AlertChannel>}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailProperties.class)
public class NotificationConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "inventory.alerts.email", name = "enabled", havingValue = "true")
    AlertChannel emailAlertChannel(JavaMailSender sender, EmailProperties properties) {
        return new EmailAlertChannel(sender, properties);
    }
}

package com.store.inventory.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class EmailAlertChannelTest {

    @Test
    void nameIsEmail() {
        assertThat(new EmailAlertChannel(mock(JavaMailSender.class), properties("a@store.local")).name())
                .isEqualTo("email");
    }

    @Test
    void sendsSubjectBodyAndRecipients() {
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailAlertChannel channel = new EmailAlertChannel(sender, properties("compras@store.local", "ops@store.local"));

        channel.onLowStock("SKU-1", 5);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("inventario@store.local");
        assertThat(message.getTo()).containsExactly("compras@store.local", "ops@store.local");
        assertThat(message.getSubject()).isEqualTo("[Inventario] Bajo stock: SKU-1 (5 disp.)");
        assertThat(message.getText()).contains("SKU: SKU-1").contains("Unidades disponibles: 5")
                .contains("Reabastecer lo antes posible.");
    }

    @Test
    void mailSendExceptionPropagates() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new MailSendException("down")).when(sender).send(any(SimpleMailMessage.class));
        EmailAlertChannel channel = new EmailAlertChannel(sender, properties("compras@store.local"));

        assertThatThrownBy(() -> channel.onLowStock("SKU-1", 4)).isInstanceOf(MailSendException.class);
    }

    @Test
    void maskHidesLocalPart() {
        assertThat(EmailAlertChannel.mask("compras@store.local")).isEqualTo("c***@store.local");
    }

    private static EmailProperties properties(String... to) {
        return new EmailProperties(true, "inventario@store.local", List.of(to), "[Inventario]");
    }
}

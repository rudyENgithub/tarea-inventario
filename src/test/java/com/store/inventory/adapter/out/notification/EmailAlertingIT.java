package com.store.inventory.adapter.out.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.store.inventory.adapter.out.notification.SafeStockAlertListener;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.application.port.out.AlertChannel;
import com.store.inventory.testsupport.ConcurrentTestSupport;
import com.store.inventory.testsupport.MutableClock;
import com.store.inventory.testsupport.RecordingAlertChannel;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
        classes = com.store.inventory.bootstrap.InventoryApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "inventory.alerts.email.enabled=true",
                "inventory.alerts.email.from=inventario@store.local",
                "inventory.alerts.email.to=compras@store.local",
                "inventory.alerts.relay.poll-interval=50ms",
                "inventory.alerts.dispatch.base-delay=10s",
                "spring.mail.host=127.0.0.1"
        })
@Import(EmailAlertingIT.TestBeans.class)
@Testcontainers(disabledWithoutDocker = true)
class EmailAlertingIT {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final GreenMail GREEN_MAIL = startMail();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void mailAndRelay(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.port", () -> GREEN_MAIL.getSmtp().getPort());
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }

        @Bean
        RecordingAlertChannel probeChannel() {
            return new RecordingAlertChannel("probe");
        }

        @Bean
        AlertChannel failingLog() {
            return new SafeStockAlertListener(AlertChannel.of("failing-log", (sku, available) -> {
                throw new RuntimeException("log down");
            }));
        }
    }

    @Autowired
    InventoryService service;

    @Autowired
    MutableClock clock;

    @Autowired
    RecordingAlertChannel probe;

    @Autowired
    EmailAlertChannel emailChannel;

    @Autowired
    JavaMailSender mailSender;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE alert_outbox, reservations, products RESTART IDENTITY CASCADE").update();
        try {
            GREEN_MAIL.purgeEmailFromAllMailboxes();
        } catch (Exception e) {
            throw new IllegalStateException("Could not purge GreenMail", e);
        }
        probe.clear();
        clock.set(T0);
        if (!GREEN_MAIL.isRunning()) {
            GREEN_MAIL.start();
        }
        pointMailSenderAtGreenMail();
        assertThat(emailChannel.name()).isEqualTo("email");
    }

    @Test
    @DisplayName("RF-A1 RF-A2 RF-A3 one email per threshold crossing")
    void happyPathOneEmailThenRearm() throws Exception {
        service.registerProduct("MAIL-1", ProductCategory.STANDARD);
        service.addStock("MAIL-1", 10);
        service.reserve("M1", "MAIL-1", 5);
        assertThat(service.available("MAIL-1")).isEqualTo(5);
        assertThat(probe.await(1, Duration.ofSeconds(5))).isTrue();
        ConcurrentTestSupport.awaitCondition(() -> GREEN_MAIL.getReceivedMessages().length == 1, Duration.ofSeconds(5));
        assertThat(GREEN_MAIL.getReceivedMessages()[0].getSubject()).contains("MAIL-1").contains("5");

        service.reserve("M2", "MAIL-1", 1);
        assertThat(service.available("MAIL-1")).isEqualTo(4);
        ConcurrentTestSupport.awaitCondition(() -> GREEN_MAIL.getReceivedMessages().length >= 1, Duration.ofSeconds(2));
        assertThat(GREEN_MAIL.getReceivedMessages()).hasSize(1);

        service.addStock("MAIL-1", 10);
        service.reserve("M3", "MAIL-1", 10);
        ConcurrentTestSupport.awaitCondition(() -> GREEN_MAIL.getReceivedMessages().length == 2, Duration.ofSeconds(5));
        assertThat(probe.alerts()).hasSize(2);
    }

    @Test
    @DisplayName("RF-A4 SMTP down then recovered")
    void smtpDownRetriesThenDelivers() throws Exception {
        GREEN_MAIL.stop();
        service.registerProduct("MAIL-2", ProductCategory.STANDARD);
        service.addStock("MAIL-2", 10);
        service.reserve("M-down", "MAIL-2", 5);
        assertThat(service.available("MAIL-2")).isEqualTo(5);
        ConcurrentTestSupport.awaitCondition(() -> outboxAttempts() >= 1 && "PENDING".equals(outboxStatus()),
                Duration.ofSeconds(15));
        assertThat(jdbc.sql("SELECT last_error FROM alert_outbox").query(String.class).single()).isNotBlank();

        GREEN_MAIL.start();
        pointMailSenderAtGreenMail();
        clock.advance(Duration.ofSeconds(10));
        ConcurrentTestSupport.awaitCondition(() -> "DELIVERED".equals(outboxStatus()), Duration.ofSeconds(5));
        assertThat(GREEN_MAIL.getReceivedMessages()).hasSize(1);
    }

    @Test
    @DisplayName("OCP extra channel receives the same alert")
    void extraChannelReceivesAlert() {
        service.registerProduct("MAIL-3", ProductCategory.STANDARD);
        service.addStock("MAIL-3", 10);
        service.reserve("M-ocp", "MAIL-3", 5);
        assertThat(probe.await(1, Duration.ofSeconds(5))).isTrue();
        ConcurrentTestSupport.awaitCondition(() -> GREEN_MAIL.getReceivedMessages().length >= 1, Duration.ofSeconds(5));
    }

    private void pointMailSenderAtGreenMail() {
        if (mailSender instanceof JavaMailSenderImpl impl) {
            impl.setHost("127.0.0.1");
            impl.setPort(GREEN_MAIL.getSmtp().getPort());
        }
    }

    private String outboxStatus() {
        return jdbc.sql("SELECT status FROM alert_outbox ORDER BY id LIMIT 1").query(String.class).optional().orElse("");
    }

    private int outboxAttempts() {
        return jdbc.sql("SELECT attempts FROM alert_outbox ORDER BY id LIMIT 1").query(Integer.class).optional().orElse(0);
    }

    private static GreenMail startMail() {
        GreenMail mail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
        mail.start();
        return mail;
    }
}

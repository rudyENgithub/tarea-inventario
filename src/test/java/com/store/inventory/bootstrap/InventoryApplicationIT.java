package com.store.inventory.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.application.alert.AlertRelay;
import com.store.inventory.testsupport.MutableClock;
import com.store.inventory.testsupport.RecordingAlertChannel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class InventoryApplicationIT {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void relay(DynamicPropertyRegistry registry) {
        registry.add("inventory.alerts.relay.poll-interval", () -> "50ms");
        registry.add("inventory.alerts.dispatch.base-delay", () -> "10s");
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }

        @Bean
        RecordingAlertChannel recordingAlertChannel() {
            return new RecordingAlertChannel("recording");
        }
    }

    @Autowired
    InventoryService service;

    @Autowired
    MutableClock clock;

    @Autowired
    RecordingAlertChannel recording;

    @Autowired
    JdbcClient jdbc;

    @LocalServerPort
    int port;

    @Autowired
    AlertRelay alertRelay;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE alert_outbox, reservations, products RESTART IDENTITY CASCADE").update();
        recording.fail(false);
        recording.clear();
        clock.set(T0);
    }

    @Test
    void contextStartsAndHealthIsUp() {
        HttpResponse<String> health = httpGet("/actuator/health");
        HttpResponse<String> ready = httpGet("/actuator/health/readiness");
        assertThat(health.statusCode()).isBetween(200, 299);
        assertThat(health.body()).contains("UP");
        assertThat(ready.statusCode()).isBetween(200, 299);
        Integer version = jdbc.sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")
                .query(Integer.class)
                .optional()
                .orElse(0);
        assertThat(version).isEqualTo(1);
    }

    @Test
    @DisplayName("RF-A1 reserve to threshold delivers one alert")
    void reserveDeliversAlertAndConfirmKeepsAvailable() {
        service.registerProduct("SKU-A", ProductCategory.STANDARD);
        service.addStock("SKU-A", 10);
        service.reserve("O1", "SKU-A", 5);
        assertThat(service.available("SKU-A")).isEqualTo(5);
        assertThat(recording.await(1, Duration.ofSeconds(5))).isTrue();
        assertThat(recording.alerts()).hasSize(1);
        service.confirm("O1");
        assertThat(service.available("SKU-A")).isEqualTo(5);
    }

    @Test
    @DisplayName("RF-R4 idempotent reserve")
    void doubleReserveSameOrderIsIdempotent() {
        service.registerProduct("SKU-B", ProductCategory.STANDARD);
        service.addStock("SKU-B", 10);
        Reservation first = service.reserve("SAME", "SKU-B", 3);
        Reservation second = service.reserve("SAME", "SKU-B", 3);
        assertThat(second).isEqualTo(first);
        assertThat(service.available("SKU-B")).isEqualTo(7);
    }

    @Test
    @DisplayName("RF-A4 failing channel does not corrupt reserve")
    void failingChannelRetriesThenDelivers() {
        recording.fail(true);
        service.registerProduct("SKU-C", ProductCategory.STANDARD);
        service.addStock("SKU-C", 10);
        service.reserve("O-fail", "SKU-C", 5);
        assertThat(service.available("SKU-C")).isEqualTo(5);
        ConcurrentWait.until(() -> outboxStatus().equals("PENDING") && outboxAttempts() >= 1, Duration.ofSeconds(5));
        assertThat(jdbc.sql("SELECT last_error FROM alert_outbox").query(String.class).single()).isNotBlank();
        recording.fail(false);
        clock.advance(Duration.ofSeconds(10));
        ConcurrentWait.until(() -> "DELIVERED".equals(outboxStatus()), Duration.ofSeconds(5));
    }

    @Test
    void relayRunsOnVirtualThreadNamedAlertRelay() {
        service.registerProduct("SKU-D", ProductCategory.STANDARD);
        service.addStock("SKU-D", 10);
        service.reserve("O-vt", "SKU-D", 5);
        assertThat(recording.await(1, Duration.ofSeconds(5))).isTrue();
        assertThat(recording.lastThread()).isNotNull();
        assertThat(recording.lastThread().isVirtual()).isTrue();
        assertThat(recording.lastThread().getName()).isEqualTo("alert-relay");
    }

    @Test
    @org.springframework.test.annotation.DirtiesContext(methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void closeStopsRelayThread() {
        alertRelay.close();
        List<Thread> leftover = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "alert-relay".equals(t.getName()) && t.isAlive())
                .toList();
        assertThat(leftover).isEmpty();
    }

    private String outboxStatus() {
        return jdbc.sql("SELECT status FROM alert_outbox ORDER BY id LIMIT 1")
                .query(String.class)
                .optional()
                .orElse("");
    }

    private int outboxAttempts() {
        return jdbc.sql("SELECT attempts FROM alert_outbox ORDER BY id LIMIT 1")
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    private HttpResponse<String> httpGet(String path) {
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("GET " + path, e);
        }
    }

    private static final class ConcurrentWait {
        static void until(java.util.function.BooleanSupplier condition, Duration timeout) {
            com.store.inventory.testsupport.ConcurrentTestSupport.awaitCondition(condition, timeout);
        }
    }
}

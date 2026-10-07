package com.store.inventory.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.store.inventory.testsupport.ConcurrentTestSupport;
import com.store.inventory.testsupport.MutableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
        classes = com.store.inventory.bootstrap.InventoryApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InventoryApiIT.TestBeans.class)
@Testcontainers(disabledWithoutDocker = true)
class InventoryApiIT {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Pattern AVAILABLE = Pattern.compile("\"available\"\\s*:\\s*(\\d+)");
    private static final Pattern ERROR_CODE = Pattern.compile("\"errorCode\"\\s*:\\s*\"([^\"]+)\"");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("inventory.alerts.relay.poll-interval", () -> "50ms");
        registry.add("inventory.database.lock-timeout", () -> "30s");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "30000");
    }

    @TestConfiguration
    static class TestBeans {
        static final AtomicBoolean virtual = new AtomicBoolean();

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }

        @Bean
        jakarta.servlet.Filter virtualThreadFilter() {
            return (request, response, chain) -> {
                virtual.set(Thread.currentThread().isVirtual());
                chain.doFilter(request, response);
            };
        }
    }

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE alert_outbox, reservations, products RESTART IDENTITY CASCADE").update();
        clock.set(T0);
    }

    @Test
    @DisplayName("RF-R1 HTTP reserve then availability")
    void reserveThenAvailability() {
        putProduct("SKU-1", "STANDARD");
        addStock("SKU-1", 10);
        HttpResponse<String> first = reserve("O1", "SKU-1", 3);
        assertThat(first.statusCode()).isEqualTo(HttpStatus.OK.value());
        HttpResponse<String> replay = reserve("O1", "SKU-1", 3);
        assertThat(replay.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(available("SKU-1")).isEqualTo(7);
        assertThat(confirm("O1").statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(confirm("O1").statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(available("SKU-1")).isEqualTo(7);
    }

    @Test
    void confirmUnknownIs409() {
        HttpResponse<String> response = confirm("missing");
        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(errorCode(response.body())).isEqualTo("NO_ACTIVE_RESERVATION");
    }

    @Test
    @DisplayName("RF-R5 FLASH_SALE limit")
    void flashSaleLimitIs422() {
        putProduct("FLASH-1", "FLASH_SALE");
        addStock("FLASH-1", 10);
        HttpResponse<String> tooMany = reserve("F1", "FLASH-1", 3);
        assertThat(tooMany.statusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
        assertThat(errorCode(tooMany.body())).isEqualTo("ORDER_LIMIT_EXCEEDED");
        HttpResponse<String> ok = reserve("F2", "FLASH-1", 2);
        assertThat(ok.statusCode()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void insufficientStockIs409() {
        putProduct("SKU-2", "STANDARD");
        addStock("SKU-2", 1);
        HttpResponse<String> response = reserve("O2", "SKU-2", 5);
        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(errorCode(response.body())).isEqualTo("INSUFFICIENT_STOCK");
    }

    @Test
    void reusedOrderIdDifferentQuantityIs400() {
        putProduct("SKU-3", "STANDARD");
        addStock("SKU-3", 10);
        reserve("O3", "SKU-3", 2);
        HttpResponse<String> response = reserve("O3", "SKU-3", 4);
        assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(errorCode(response.body())).isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("RF-C2 expired reservation cannot be confirmed")
    void expiredConfirmIs409() {
        putProduct("SKU-4", "FLASH_SALE");
        addStock("SKU-4", 10);
        reserve("O4", "SKU-4", 1);
        clock.advance(Duration.ofMinutes(5));
        HttpResponse<String> response = confirm("O4");
        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(errorCode(response.body())).isEqualTo("NO_ACTIVE_RESERVATION");
    }

    @Test
    void unknownSkuAvailabilityIsZero() {
        assertThat(available("NOPE")).isEqualTo(0);
    }

    @Test
    void requestRunsOnVirtualThread() {
        get("/api/v1/products/NOPE/availability");
        assertThat(TestBeans.virtual.get()).isTrue();
    }

    @Test
    @DisplayName("RNF-1 HTTP concurrency")
    void twoHundredReservesOnFiftyUnits() throws Exception {
        putProduct("HOT", "STANDARD");
        addStock("HOT", 50);
        String url = "http://localhost:" + port + "/api/v1/reservations";
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                .build()) {
            ConcurrentTestSupport.runConcurrently(200, Duration.ofSeconds(30), i -> () -> {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"orderId\":\"C" + i + "\",\"sku\":\"HOT\",\"quantity\":1}"))
                        .build();
                HttpResponse<String> response = sendWithRetry(client, request);
                if (response.statusCode() == 200) {
                    ok.incrementAndGet();
                } else if (response.statusCode() == 409) {
                    conflict.incrementAndGet();
                } else {
                    throw new IllegalStateException("unexpected " + response.statusCode() + " " + response.body());
                }
                return response.statusCode();
            });
        }
        assertThat(ok.get()).isEqualTo(50);
        assertThat(conflict.get()).isEqualTo(150);
    }

    @Test
    void tenReplaysDoNotDoubleCharge() {
        putProduct("SKU-R", "STANDARD");
        addStock("SKU-R", 10);
        String first = reserve("OR", "SKU-R", 3).body();
        for (int i = 0; i < 9; i++) {
            HttpResponse<String> replay = reserve("OR", "SKU-R", 3);
            assertThat(replay.statusCode()).isEqualTo(HttpStatus.OK.value());
            assertThat(replay.body()).isEqualTo(first);
        }
        assertThat(available("SKU-R")).isEqualTo(7);
    }

    private void putProduct(String sku, String category) {
        send("PUT", "/api/v1/products/" + sku, "{\"category\":\"" + category + "\"}");
    }

    private void addStock(String sku, int quantity) {
        HttpResponse<String> response = send("POST", "/api/v1/products/" + sku + "/stock",
                "{\"quantity\":" + quantity + "}");
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    }

    private HttpResponse<String> reserve(String orderId, String sku, int quantity) {
        return send("POST", "/api/v1/reservations",
                "{\"orderId\":\"" + orderId + "\",\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}");
    }

    private HttpResponse<String> confirm(String orderId) {
        return send("POST", "/api/v1/reservations/" + orderId + "/confirm", null);
    }

    private int available(String sku) {
        HttpResponse<String> response = get("/api/v1/products/" + sku + "/availability");
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        Matcher matcher = AVAILABLE.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private HttpResponse<String> get(String path) {
        return send("GET", path, null);
    }

    private HttpResponse<String> send(String method, String path, String jsonBody) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            HttpRequest request = switch (method) {
                case "GET" -> builder.GET().build();
                case "PUT" -> builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .PUT(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
                case "POST" -> jsonBody == null
                        ? builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                        : builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
                default -> throw new IllegalArgumentException(method);
            };
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("HTTP " + method + " " + path, e);
        }
    }

    private HttpResponse<String> sendWithRetry(HttpClient client, HttpRequest request) throws Exception {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 503) {
                return response;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
        return response;
    }

    private static String errorCode(String body) {
        Matcher matcher = ERROR_CODE.matcher(body);
        assertThat(matcher.find()).as(body).isTrue();
        return matcher.group(1);
    }
}

package com.store.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.Inventory;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.testsupport.MutableClock;
import com.store.inventory.testsupport.RecordingAlertListener;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DefaultInventoryServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final RecordingAlertListener listener = new RecordingAlertListener();
    private final InventoryService service = Inventory.create(clock, listener);

    private void product(String sku, ProductCategory category, int stock) {
        service.registerProduct(sku, category);
        service.addStock(sku, stock);
    }

    // --- RF-S -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RF-S1: a registered product accepts stock")
    void registeredProductAcceptsStock() {
        service.registerProduct("A", ProductCategory.STANDARD);

        assertThatCode(() -> service.addStock("A", 1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("RF-S2: adding stock makes the units available")
    void addStockMakesUnitsAvailable() {
        service.registerProduct("A", ProductCategory.STANDARD);

        service.addStock("A", 10);

        assertThat(service.available("A")).isEqualTo(10);
    }

    @Test
    @DisplayName("RF-S3: non-positive quantities and unregistered products are rejected by addStock")
    void addStockValidation() {
        service.registerProduct("A", ProductCategory.STANDARD);

        assertThatThrownBy(() -> service.addStock("A", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addStock("A", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addStock("X", 5)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
        assertThatThrownBy(() -> service.addStock(null, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addStock(" ", 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RF-S4: an unknown product has zero availability")
    void unknownProductHasNone() {
        assertThat(service.available("NOEXISTE")).isZero();
        assertThat(service.available("")).isZero();
        assertThatThrownBy(() -> service.available(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("D7: registering twice is a no-op for the same category and an error for a different one")
    void registeringTwice() {
        service.registerProduct("A", ProductCategory.FLASH_SALE);
        service.addStock("A", 3);

        assertThatCode(() -> service.registerProduct("A", ProductCategory.FLASH_SALE)).doesNotThrowAnyException();
        assertThat(service.available("A")).isEqualTo(3);
        assertThatThrownBy(() -> service.registerProduct("A", ProductCategory.STANDARD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered with category FLASH_SALE");
        assertThatThrownBy(() -> service.registerProduct("B", null)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- RF-R -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RF-R1: reserving removes the units from the available stock at once")
    void reserveReducesAvailability() {
        product("A", ProductCategory.STANDARD, 10);

        service.reserve("O1", "A", 3);

        assertThat(service.available("A")).isEqualTo(7);
    }

    @Test
    @DisplayName("RF-R2: reserving more than available fails and leaves the stock untouched")
    void cannotReserveMoreThanAvailable() {
        product("A", ProductCategory.STANDARD, 2);

        assertThatThrownBy(() -> service.reserve("O1", "A", 3))
                .isInstanceOfSatisfying(InsufficientStockException.class, e -> {
                    assertThat(e.getMessage()).contains("A");
                });
        assertThat(service.available("A")).isEqualTo(2);
    }

    @Test
    @DisplayName("RF-R2: an unknown product has no units to reserve")
    void unknownProductCannotBeReserved() {
        assertThatThrownBy(() -> service.reserve("O1", "NOPE", 1)).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    @DisplayName("RF-R3: non-positive quantities and blank arguments are rejected")
    void reserveValidation() {
        product("A", ProductCategory.STANDARD, 10);

        assertThatThrownBy(() -> service.reserve("O1", "A", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve("O1", "A", -2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve(null, "A", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve(" ", "A", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve("O1", null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve("O1", "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.available("A")).isEqualTo(10);
    }

    @Test
    @DisplayName("RF-R4: repeating the same order returns the same reservation and discounts only once")
    void reserveIsIdempotent() {
        product("A", ProductCategory.STANDARD, 10);

        Reservation first = service.reserve("O1", "A", 3);
        clock.advance(Duration.ofMinutes(1));
        Reservation second = service.reserve("O1", "A", 3);

        assertThat(second).isEqualTo(first);
        assertThat(service.available("A")).isEqualTo(7);
    }

    @Test
    @DisplayName("RF-R4/D8: the same order with another quantity is rejected")
    void sameOrderDifferentQuantity() {
        product("A", ProductCategory.STANDARD, 10);
        service.reserve("O1", "A", 3);

        assertThatThrownBy(() -> service.reserve("O1", "A", 4)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.available("A")).isEqualTo(7);
    }

    @Test
    @DisplayName("D6: the same order on another SKU is rejected")
    void sameOrderOtherSku() {
        product("A", ProductCategory.STANDARD, 10);
        product("B", ProductCategory.STANDARD, 10);
        service.reserve("O1", "A", 3);

        assertThatThrownBy(() -> service.reserve("O1", "B", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.available("A")).isEqualTo(7);
        assertThat(service.available("B")).isEqualTo(10);
    }

    @Test
    @DisplayName("RF-R5: FLASH_SALE allows at most 2 units per order")
    void flashSaleLimit() {
        product("F", ProductCategory.FLASH_SALE, 10);

        assertThatThrownBy(() -> service.reserve("O1", "F", 3))
                .isInstanceOfSatisfying(OrderLimitExceededException.class,
                        e -> assertThat(e.getMessage()).contains("F"));
        assertThatCode(() -> service.reserve("O2", "F", 2)).doesNotThrowAnyException();
        assertThat(service.available("F")).isEqualTo(8);
    }

    @Test
    @DisplayName("RF-R5: the limit is checked before the stock")
    void limitBeforeStock() {
        product("F", ProductCategory.FLASH_SALE, 1);

        assertThatThrownBy(() -> service.reserve("O1", "F", 3)).isInstanceOf(OrderLimitExceededException.class);
    }

    @ParameterizedTest
    @CsvSource({"STANDARD,PT15M", "PRE_ORDER,PT24H", "FLASH_SALE,PT5M"})
    @DisplayName("RF-R6: expiresAt is the injected clock plus the payment window of the category")
    void expirationWindow(ProductCategory category, Duration window) {
        product("A", category, 5);

        Reservation reservation = service.reserve("O1", "A", 1);

        assertThat(reservation.expiresAt()).isEqualTo(T0.plus(window));
        assertThat(reservation.orderId()).isEqualTo("O1");
        assertThat(reservation.sku()).isEqualTo("A");
        assertThat(reservation.quantity()).isEqualTo(1);
    }

    @Test
    @DisplayName("a rejected reservation neither raises alerts nor changes state")
    void rejectedReservationIsInvisible() {
        product("F", ProductCategory.FLASH_SALE, 3);

        assertThatThrownBy(() -> service.reserve("O1", "F", 3)).isInstanceOf(OrderLimitExceededException.class);
        assertThatThrownBy(() -> service.reserve("O2", "F", 5)).isInstanceOf(OrderLimitExceededException.class);

        assertThat(listener.count()).isZero();
        assertThat(service.available("F")).isEqualTo(3);
    }

    // --- RF-E -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RF-E1/RF-E2: units come back exactly when the reservation expires")
    void unitsReturnAtExpiration() {
        product("A", ProductCategory.STANDARD, 10);
        Reservation reservation = service.reserve("O1", "A", 4);
        assertThat(service.available("A")).isEqualTo(6);

        clock.set(reservation.expiresAt().minusNanos(1_000));
        assertThat(service.available("A")).isEqualTo(6);

        clock.set(reservation.expiresAt());
        assertThat(service.available("A")).isEqualTo(10);
    }

    @Test
    @DisplayName("RF-E3: with a fixed clock the outcome is reproducible")
    void deterministicWithFixedClock() {
        product("A", ProductCategory.STANDARD, 10);
        Reservation first = service.reserve("O1", "A", 4);

        var other = Inventory.create(new MutableClock(T0), (sku, available) -> { });
        other.registerProduct("A", ProductCategory.STANDARD);
        other.addStock("A", 10);
        Reservation second = other.reserve("O1", "A", 4);

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("assumption 8: after expiring, the same order id can reserve again")
    void reserveAgainAfterExpiration() {
        product("A", ProductCategory.STANDARD, 10);
        service.reserve("O1", "A", 4);
        clock.advance(Duration.ofMinutes(20));

        Reservation again = service.reserve("O1", "A", 6);

        assertThat(again.quantity()).isEqualTo(6);
        assertThat(again.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(service.available("A")).isEqualTo(4);
    }

    // --- RF-C -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RF-C1: confirmed units never return to the stock")
    void confirmedUnitsStaySold() {
        product("A", ProductCategory.STANDARD, 5);
        service.reserve("O1", "A", 2);

        service.confirm("O1");
        assertThat(service.available("A")).isEqualTo(3);

        clock.advance(Duration.ofDays(2));
        assertThat(service.available("A")).isEqualTo(3);
    }

    @Test
    @DisplayName("RF-C2: confirming an unknown order fails")
    void confirmUnknown() {
        assertThatThrownBy(() -> service.confirm("nope")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.confirm(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RF-C2: confirming an expired reservation fails and the units stay available")
    void confirmExpired() {
        product("A", ProductCategory.STANDARD, 5);
        Reservation reservation = service.reserve("O1", "A", 2);
        clock.set(reservation.expiresAt());

        assertThatThrownBy(() -> service.confirm("O1")).isInstanceOf(IllegalStateException.class);
        assertThat(service.available("A")).isEqualTo(5);
    }

    @Test
    @DisplayName("RF-C3: confirming twice is a no-op")
    void confirmTwice() {
        product("A", ProductCategory.STANDARD, 5);
        service.reserve("O1", "A", 2);
        service.confirm("O1");

        assertThatCode(() -> service.confirm("O1")).doesNotThrowAnyException();
        assertThat(service.available("A")).isEqualTo(3);
    }

    @Test
    @DisplayName("RF-C3: reserving again a confirmed order returns the confirmed reservation without discounting")
    void replayOnConfirmedReservation() {
        product("A", ProductCategory.STANDARD, 5);
        Reservation reservation = service.reserve("O1", "A", 2);
        service.confirm("O1");
        clock.advance(Duration.ofDays(1));

        Reservation replay = service.reserve("O1", "A", 2);

        assertThat(replay).isEqualTo(reservation);
        assertThat(service.available("A")).isEqualTo(3);
        assertThatThrownBy(() -> service.reserve("O1", "A", 3)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- RF-A -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RF-A1: one alert, delivered before reserve returns, when availability drops to 5")
    void alertWhenCrossingTheThreshold() {
        product("A", ProductCategory.STANDARD, 10);

        service.reserve("O1", "A", 4);
        assertThat(listener.count()).isZero();
        service.reserve("O2", "A", 1);

        assertThat(listener.alerts()).containsExactly(new RecordingAlertListener.Alert("A", 5));
    }

    @Test
    @DisplayName("RF-A2: no repeated alert while the product is not restocked")
    void noRepeatedAlert() {
        product("A", ProductCategory.STANDARD, 10);
        service.reserve("O1", "A", 5);

        service.reserve("O2", "A", 1);
        service.reserve("O3", "A", 1);

        assertThat(listener.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("RF-A2: replaying a reservation does not raise another alert")
    void replayDoesNotAlert() {
        product("A", ProductCategory.STANDARD, 10);
        service.reserve("O1", "A", 5);
        service.reserve("O1", "A", 5);

        assertThat(listener.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("RF-A3: restocking above the threshold re-arms the alert")
    void restockRearms() {
        product("A", ProductCategory.STANDARD, 10);
        service.reserve("O1", "A", 5);
        service.addStock("A", 3);

        service.reserve("O2", "A", 3);

        assertThat(listener.alerts()).containsExactly(
                new RecordingAlertListener.Alert("A", 5), new RecordingAlertListener.Alert("A", 5));
    }

    @Test
    @DisplayName("D4: a product that starts with 3 units alerts on its first reservation")
    void lowProductAlertsOnFirstReservation() {
        product("A", ProductCategory.STANDARD, 3);

        service.reserve("O1", "A", 1);

        assertThat(listener.alerts()).containsExactly(new RecordingAlertListener.Alert("A", 2));
    }

    @Test
    @DisplayName("assumption 6: expiration does not re-arm the alert")
    void expirationDoesNotRearm() {
        product("A", ProductCategory.STANDARD, 10);
        service.reserve("O1", "A", 6);
        clock.advance(Duration.ofMinutes(20));
        assertThat(service.available("A")).isEqualTo(10);

        service.reserve("O2", "A", 6);

        assertThat(listener.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("RF-A4: a listener that throws does not affect the reservation nor later operations")
    void failingListenerIsContained() {
        var failing = RecordingAlertListener.failing(new IllegalStateException("purchasing is down"));
        var failingService = Inventory.create(clock, failing);
        failingService.registerProduct("A", ProductCategory.STANDARD);
        failingService.addStock("A", 10);

        Reservation reservation = failingService.reserve("O1", "A", 6);

        assertThat(reservation.quantity()).isEqualTo(6);
        assertThat(failingService.available("A")).isEqualTo(4);
        assertThat(failing.count()).isEqualTo(1);
        assertThatCode(() -> failingService.reserve("O2", "A", 1)).doesNotThrowAnyException();
        failingService.confirm("O1");
        assertThat(failingService.available("A")).isEqualTo(3);
    }

    @RepeatedTest(20)
    @DisplayName("RF-A4/RNF-5: the listener runs outside the critical section")
    void alertIsDeliveredOutsideTheCriticalSection() throws Exception {
        AtomicReference<InventoryService> holder = new AtomicReference<>();
        CompletableFuture<Integer> availableFromListener = new CompletableFuture<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            StockAlertListener reentrant = (sku, available) -> {
                Future<Integer> inner = executor.submit(() -> holder.get().available(sku));
                try {
                    availableFromListener.complete(inner.get(2, TimeUnit.SECONDS));
                } catch (Exception e) {
                    availableFromListener.completeExceptionally(e);
                }
            };
            InventoryService reentrantService = Inventory.create(clock, reentrant);
            holder.set(reentrantService);
            reentrantService.registerProduct("A", ProductCategory.STANDARD);
            reentrantService.addStock("A", 10);

            reentrantService.reserve("O1", "A", 6);

            assertThat(availableFromListener.get(5, TimeUnit.SECONDS)).isEqualTo(4);
        }
    }
}

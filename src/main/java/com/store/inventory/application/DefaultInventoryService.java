package com.store.inventory.application;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.application.alert.AlertDispatchTrigger;
import com.store.inventory.application.port.out.InventoryRepository;
import com.store.inventory.application.port.out.OperationResult;
import com.store.inventory.application.port.out.OrderIdConflictException;
import com.store.inventory.domain.alert.LowStockAlert;
import com.store.inventory.domain.alert.LowStockDetector;
import com.store.inventory.domain.policy.CategoryPolicyRegistry;
import com.store.inventory.domain.stock.ReserveOutcome;
import com.store.inventory.domain.stock.ReservationStatus;
import com.store.inventory.domain.stock.StockReservation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fachada de los casos de uso del inventario. Solo orquesta: las reglas de negocio viven en el agregado de dominio,
 * la atomicidad en el repositorio y la entrega en el despachador de alertas. La hora actual se lee dentro de la
 * sección crítica.
 */
public final class DefaultInventoryService implements InventoryService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultInventoryService.class);

    private record Reserved(Reservation reservation, boolean alertRaised) {
    }

    private final InventoryRepository repository;
    private final CategoryPolicyRegistry policies;
    private final LowStockDetector detector;
    private final AlertDispatchTrigger trigger;
    private final Clock clock;

    public DefaultInventoryService(InventoryRepository repository, CategoryPolicyRegistry policies,
            LowStockDetector detector, AlertDispatchTrigger trigger, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        requireText(sku, "sku");
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        ProductCategory effective = repository.registerIfAbsent(sku, category);
        if (effective != category) {
            throw new IllegalArgumentException("Product " + sku + " is already registered with category " + effective);
        }
    }

    @Override
    public void addStock(String sku, int quantity) {
        requireText(sku, "sku");
        requirePositive(quantity);
        Optional<Boolean> done = repository.executeOnProduct(sku, product -> {
            Instant now = clock.instant();
            product.addStock(quantity);
            detector.afterRestock(product, now);
            return OperationResult.of(Boolean.TRUE);
        });
        if (done.isEmpty()) {
            throw new IllegalArgumentException("Product " + sku + " is not registered");
        }
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        requireText(orderId, "orderId");
        requireText(sku, "sku");
        requirePositive(quantity);
        rejectOrderBoundToAnotherSku(orderId, sku);

        Reserved reserved = reserveAtomically(orderId, sku, quantity)
                .orElseThrow(() -> new InsufficientStockException(sku, quantity, 0));
        if (reserved.alertRaised()) {
            notifyTrigger();
        }
        return reserved.reservation();
    }

    @Override
    public void confirm(String orderId) {
        requireText(orderId, "orderId");
        StockReservation existing = repository.findReservation(orderId)
                .orElseThrow(() -> noActiveReservation(orderId));
        if (existing.status() == ReservationStatus.CONFIRMED) {
            return;
        }
        repository.executeOnProduct(existing.sku(), orderId, product -> {
            Instant now = clock.instant();
            product.purgeExpired(now);
            product.confirm(orderId, now);
            return OperationResult.of(Boolean.TRUE);
        }).orElseThrow(() -> noActiveReservation(orderId));
    }

    @Override
    public int available(String sku) {
        if (sku == null) {
            throw new IllegalArgumentException("sku must not be null");
        }
        return repository.availableUnits(sku, clock.instant());
    }

    private Optional<Reserved> reserveAtomically(String orderId, String sku, int quantity) {
        try {
            return repository.executeOnProduct(sku, orderId, product -> {
                Instant now = clock.instant();
                product.purgeExpired(now);
                var policy = policies.forCategory(product.category());
                ReserveOutcome outcome = product.reserve(orderId, quantity, policy, now);
                List<LowStockAlert> alerts = outcome.created()
                        ? detector.afterNewReservation(product, now).stream().toList()
                        : List.of();
                return new OperationResult<>(new Reserved(outcome.reservation().toContract(), !alerts.isEmpty()),
                        alerts);
            });
        } catch (OrderIdConflictException e) {
            throw new IllegalArgumentException("Order " + orderId + " is already bound to another product", e);
        }
    }

    private void rejectOrderBoundToAnotherSku(String orderId, String sku) {
        repository.findReservation(orderId)
                .filter(existing -> !existing.sku().equals(sku))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException(
                            "Order " + orderId + " already belongs to product " + existing.sku());
                });
    }

    private void notifyTrigger() {
        try {
            trigger.alertsEnqueued();
        } catch (RuntimeException e) {
            LOG.warn("Alert dispatch trigger failed; alerts stay in the outbox", e);
        }
    }

    private static IllegalStateException noActiveReservation(String orderId) {
        return new IllegalStateException("No active reservation for order " + orderId);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
    }
}

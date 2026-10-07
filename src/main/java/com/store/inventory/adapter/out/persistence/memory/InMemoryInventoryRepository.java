package com.store.inventory.adapter.out.persistence.memory;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.application.port.out.InventoryRepository;
import com.store.inventory.application.port.out.OperationResult;
import com.store.inventory.application.port.out.OrderIdConflictException;
import com.store.inventory.application.port.out.StockOperation;
import com.store.inventory.domain.alert.LowStockAlert;
import com.store.inventory.domain.stock.ProductStock;
import com.store.inventory.domain.stock.ReservationStatus;
import com.store.inventory.domain.stock.StockChanges;
import com.store.inventory.domain.stock.StockReservation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link InventoryRepository} en memoria: un {@link ReentrantLock} por SKU, así que SKUs distintos nunca se bloquean
 * entre sí. Como máximo se mantiene un lock de SKU a la vez. El lock es solo en proceso: no protege varias JVMs.
 */
public final class InMemoryInventoryRepository implements InventoryRepository {

    private static final class SkuSlot {
        private final ReentrantLock lock = new ReentrantLock();
        private final ProductCategory category;
        private int onHand;
        private boolean alertArmed = true;
        private final Map<String, StockReservation> reservations = new HashMap<>();

        private SkuSlot(ProductCategory category) {
            this.category = category;
        }
    }

    private final InMemoryAlertOutbox outbox;
    private final ConcurrentHashMap<String, SkuSlot> slots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> orderToSku = new ConcurrentHashMap<>();

    public InMemoryInventoryRepository(InMemoryAlertOutbox outbox) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public ProductCategory registerIfAbsent(String sku, ProductCategory category) {
        requireText(sku, "sku");
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        return slots.computeIfAbsent(sku, key -> new SkuSlot(category)).category;
    }

    @Override
    public Optional<StockReservation> findReservation(String orderId) {
        requireText(orderId, "orderId");
        String sku = orderToSku.get(orderId);
        SkuSlot slot = sku == null ? null : slots.get(sku);
        if (slot == null) {
            return Optional.empty();
        }
        slot.lock.lock();
        try {
            return Optional.ofNullable(slot.reservations.get(orderId));
        } finally {
            slot.lock.unlock();
        }
    }

    @Override
    public int availableUnits(String sku, Instant now) {
        SkuSlot slot = slots.get(sku);
        if (slot == null) {
            return 0;
        }
        slot.lock.lock();
        try {
            return rehydrate(sku, slot, null).availableAt(now);
        } finally {
            slot.lock.unlock();
        }
    }

    @Override
    public <R> Optional<R> executeOnProduct(String sku, StockOperation<R> operation) {
        return executeOnProduct(sku, null, operation);
    }

    @Override
    public <R> Optional<R> executeOnProduct(String sku, String orderId, StockOperation<R> operation) {
        Objects.requireNonNull(operation, "operation");
        SkuSlot slot = slots.get(sku);
        if (slot == null) {
            return Optional.empty();
        }
        slot.lock.lock();
        try {
            ProductStock product = rehydrate(sku, slot, orderId);
            OperationResult<R> result = operation.apply(product);
            persist(sku, slot, product, result.alerts());
            return Optional.ofNullable(result.value());
        } finally {
            slot.lock.unlock();
        }
    }

    private static ProductStock rehydrate(String sku, SkuSlot slot, String orderIdOfInterest) {
        List<StockReservation> loaded = new ArrayList<>();
        for (StockReservation reservation : slot.reservations.values()) {
            if (reservation.status() == ReservationStatus.ACTIVE || reservation.orderId().equals(orderIdOfInterest)) {
                loaded.add(reservation);
            }
        }
        return ProductStock.rehydrate(sku, slot.category, slot.onHand, slot.alertArmed, loaded);
    }

    private void persist(String sku, SkuSlot slot, ProductStock product, List<LowStockAlert> alerts) {
        StockChanges changes = product.pullChanges();
        bindOrders(sku, changes);
        for (String orderId : changes.removedOrderIds()) {
            slot.reservations.remove(orderId);
            orderToSku.remove(orderId, sku);
        }
        for (StockReservation upsert : changes.upserts()) {
            slot.reservations.put(upsert.orderId(), upsert);
        }
        slot.onHand = product.onHand();
        slot.alertArmed = product.alertArmed();
        alerts.forEach(outbox::enqueue);
    }

    /**
     * Liga cada orden insertada/actualizada al SKU, o deshace sus propias ligas y falla si alguna pertenece a otro SKU.
     */
    private void bindOrders(String sku, StockChanges changes) {
        List<String> created = new ArrayList<>();
        for (StockReservation upsert : changes.upserts()) {
            String owner = orderToSku.putIfAbsent(upsert.orderId(), sku);
            if (owner == null) {
                created.add(upsert.orderId());
            } else if (!owner.equals(sku)) {
                created.forEach(orderToSku::remove);
                throw new OrderIdConflictException(upsert.orderId());
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

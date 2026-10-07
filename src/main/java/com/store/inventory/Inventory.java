package com.store.inventory;

import com.store.inventory.adapter.out.notification.CompositeStockAlertListener;
import com.store.inventory.adapter.out.notification.SafeStockAlertListener;
import com.store.inventory.adapter.out.persistence.memory.InMemoryAlertOutbox;
import com.store.inventory.adapter.out.persistence.memory.InMemoryInventoryRepository;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.application.DefaultInventoryService;
import com.store.inventory.application.alert.AlertDispatcher;
import com.store.inventory.application.alert.AlertRetryPolicy;
import com.store.inventory.application.alert.SynchronousAlertDispatchTrigger;
import com.store.inventory.application.port.out.AlertChannel;
import com.store.inventory.domain.alert.LowStockDetector;
import com.store.inventory.domain.alert.LowStockThreshold;
import com.store.inventory.domain.policy.CategoryPolicyRegistry;
import com.store.inventory.domain.policy.DefaultCategoryPolicies;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Punto de entrada usado por nuestras pruebas automatizadas. Mantén esta firma exactamente como está
 * y construye tu implementación aquí.
 *
 * <p>Raíz de composición del modo en memoria: cada llamada devuelve un servicio independiente. El listener se
 * invoca de forma síncrona, después de salir de la sección crítica del SKU, y sus fallos nunca llegan al llamador.
 */
public final class Inventory {

    private static final int DISPATCH_BATCH_SIZE = 50;
    private static final Duration DISPATCH_LEASE = Duration.ofSeconds(30);

    private Inventory() {
    }

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(alertListener, "alertListener");

        var outbox = new InMemoryAlertOutbox();
        var repository = new InMemoryInventoryRepository(outbox);
        var policies = CategoryPolicyRegistry.of(DefaultCategoryPolicies.all());
        var detector = new LowStockDetector(LowStockThreshold.DEFAULT);
        StockAlertListener chain = new CompositeStockAlertListener(
                List.of(new SafeStockAlertListener(AlertChannel.of("custom", alertListener))));
        var dispatcher = new AlertDispatcher(outbox, chain, clock, AlertRetryPolicy.noRetry(), DISPATCH_BATCH_SIZE,
                DISPATCH_LEASE);
        var trigger = new SynchronousAlertDispatchTrigger(dispatcher);
        return new DefaultInventoryService(repository, policies, detector, trigger, clock);
    }
}

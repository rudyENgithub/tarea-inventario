package com.store.inventory.adapter.out.persistence.memory;

import com.store.inventory.application.port.out.InventoryRepository;
import com.store.inventory.application.port.out.InventoryRepositoryContractTest;
import com.store.inventory.domain.alert.LowStockAlert;
import java.util.List;

class InMemoryInventoryRepositoryTest extends InventoryRepositoryContractTest {

    private final InMemoryAlertOutbox outbox = new InMemoryAlertOutbox();
    private final InMemoryInventoryRepository repository = new InMemoryInventoryRepository(outbox);

    @Override
    protected InventoryRepository repository() {
        return repository;
    }

    @Override
    protected List<LowStockAlert> enqueuedAlerts() {
        return outbox.entries().stream().map(InMemoryAlertOutbox.Entry::alert).toList();
    }
}

package com.store.inventory.adapter.out.persistence.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.store.inventory.application.port.out.AlertOutbox;
import com.store.inventory.application.port.out.AlertOutboxContractTest;
import com.store.inventory.domain.alert.LowStockAlert;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class InMemoryAlertOutboxTest extends AlertOutboxContractTest {

    private final InMemoryAlertOutbox outbox = new InMemoryAlertOutbox();

    @Override
    protected AlertOutbox outbox() {
        return outbox;
    }

    @Override
    protected long enqueue(LowStockAlert alert) {
        return outbox.enqueue(alert);
    }

    @Override
    protected String statusOf(long id) {
        return outbox.entries().stream()
                .filter(entry -> entry.id() == id)
                .findFirst()
                .orElseThrow()
                .status()
                .name();
    }

    @Test
    void idsStartAtOneAndGrow() {
        long first = outbox.enqueue(new LowStockAlert("A", 1, T0));
        long second = outbox.enqueue(new LowStockAlert("B", 2, T0));

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
    }

    @Test
    void entriesExposeAnImmutableSnapshot() {
        long id = outbox.enqueue(new LowStockAlert("A", 1, T0));
        outbox.claimDue(T0, 1, Duration.ofSeconds(5));
        outbox.markRetry(id, T0.plusSeconds(1), "boom");

        var entries = outbox.entries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo(InMemoryAlertOutbox.Status.PENDING);
        assertThat(entries.get(0).attempts()).isEqualTo(1);
        assertThat(entries.get(0).lastError()).isEqualTo("boom");
        assertThatThrownBy(() -> entries.add(entries.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void errorsAreTruncatedTo1000Characters() {
        long id = outbox.enqueue(new LowStockAlert("A", 1, T0));

        outbox.markFailed(id, "x".repeat(5_000));

        assertThat(outbox.entries().get(0).lastError()).hasSize(1_000);
    }
}

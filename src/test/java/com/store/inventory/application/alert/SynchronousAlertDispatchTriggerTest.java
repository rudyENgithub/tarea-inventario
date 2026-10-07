package com.store.inventory.application.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.store.inventory.adapter.out.persistence.memory.InMemoryAlertOutbox;
import com.store.inventory.domain.alert.LowStockAlert;
import com.store.inventory.testsupport.MutableClock;
import com.store.inventory.testsupport.RecordingAlertListener;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SynchronousAlertDispatchTriggerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryAlertOutbox outbox = new InMemoryAlertOutbox();
    private final MutableClock clock = new MutableClock(T0);

    private SynchronousAlertDispatchTrigger trigger(com.store.inventory.api.StockAlertListener listener) {
        var dispatcher = new AlertDispatcher(outbox, listener, clock, AlertRetryPolicy.noRetry(), 10,
                Duration.ofMinutes(1));
        return new SynchronousAlertDispatchTrigger(dispatcher);
    }

    @Test
    @DisplayName("the listener has already been invoked when alertsEnqueued returns")
    void deliversBeforeReturning() {
        var listener = new RecordingAlertListener();
        var trigger = trigger(listener);
        outbox.enqueue(new LowStockAlert("A", 4, T0));

        trigger.alertsEnqueued();

        assertThat(listener.alerts()).containsExactly(new RecordingAlertListener.Alert("A", 4));
    }

    @Test
    @DisplayName("RF-A4: a failing listener never makes alertsEnqueued throw")
    void neverThrows() {
        var trigger = trigger(RecordingAlertListener.failing(new IllegalStateException("down")));
        outbox.enqueue(new LowStockAlert("A", 4, T0));

        assertThatCode(trigger::alertsEnqueued).doesNotThrowAnyException();
    }
}

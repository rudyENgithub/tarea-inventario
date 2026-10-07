package com.store.inventory.application.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AsyncAlertDispatchTriggerTest {

    @Test
    void alertsEnqueuedWakesTheRelay() throws Exception {
        Semaphore passes = new Semaphore(0);
        try (AlertRelay relay = new AlertRelay(() -> {
            passes.release();
            return 0;
        }, Duration.ofHours(1))) {
            relay.start();
            assertThat(passes.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
            var trigger = new AsyncAlertDispatchTrigger(relay);

            trigger.alertsEnqueued();

            assertThat(passes.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void neverThrowsEvenIfTheRelayIsClosed() {
        var relay = new AlertRelay(() -> 0, Duration.ofHours(1));
        relay.close();

        assertThatCode(() -> new AsyncAlertDispatchTrigger(relay).alertsEnqueued()).doesNotThrowAnyException();
    }
}

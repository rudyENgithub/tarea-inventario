package com.store.inventory.testsupport;

import com.store.inventory.application.port.out.AlertChannel;
import java.time.Duration;
import java.util.List;

/** {@link AlertChannel} que registra las entregas y puede configurarse para fallar. */
public final class RecordingAlertChannel implements AlertChannel {

    private final String name;
    private final RecordingAlertListener listener = new RecordingAlertListener();
    private volatile boolean fail;
    private volatile Thread lastThread;

    public RecordingAlertChannel(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        lastThread = Thread.currentThread();
        listener.onLowStock(sku, availableUnits);
        if (fail) {
            throw new RuntimeException("channel " + name + " down");
        }
    }

    public void fail(boolean value) {
        this.fail = value;
    }

    public void clear() {
        listener.clear();
        lastThread = null;
    }

    public RecordingAlertListener listener() {
        return listener;
    }

    public Thread lastThread() {
        return lastThread;
    }

    public boolean await(int expectedCount, Duration timeout) {
        return listener.await(expectedCount, timeout);
    }

    public List<RecordingAlertListener.Alert> alerts() {
        return listener.alerts();
    }
}

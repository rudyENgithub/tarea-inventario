package com.store.inventory.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** {@link Clock} thread-safe controlado por las pruebas. Siempre UTC. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        this.now = new AtomicReference<>(Objects.requireNonNull(start, "start"));
    }

    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        now.updateAndGet(current -> current.plus(duration));
    }

    public void set(Instant instant) {
        now.set(Objects.requireNonNull(instant, "instant"));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** Solo se admite UTC: devuelve {@code this} para UTC y rechaza cualquier otra zona. */
    @Override
    public Clock withZone(ZoneId zone) {
        if (ZoneOffset.UTC.equals(zone)) {
            return this;
        }
        throw new UnsupportedOperationException("MutableClock is always UTC");
    }
}

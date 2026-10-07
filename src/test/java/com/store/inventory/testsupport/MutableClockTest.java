package com.store.inventory.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MutableClockTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("RNF-4: time is advanced without sleeping")
    void advancesAndSetsTime() {
        var clock = new MutableClock(T0);
        assertThat(clock.instant()).isEqualTo(T0);

        clock.advance(Duration.ofMinutes(15));
        assertThat(clock.instant()).isEqualTo(T0.plusSeconds(900));

        clock.set(T0);
        assertThat(clock.instant()).isEqualTo(T0);
    }

    @Test
    void isAlwaysUtc() {
        Clock clock = new MutableClock(T0);
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.withZone(ZoneOffset.UTC)).isSameAs(clock);
        assertThatThrownBy(() -> clock.withZone(ZoneId.of("America/Guatemala")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void concurrentAdvancesAreExact() throws Exception {
        var clock = new MutableClock(T0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 100)
                    .mapToObj(i -> executor.submit(() -> clock.advance(Duration.ofMillis(1))))
                    .toList();
            for (var future : futures) {
                future.get();
            }
        }
        assertThat(clock.instant()).isEqualTo(T0.plusMillis(100));
    }
}

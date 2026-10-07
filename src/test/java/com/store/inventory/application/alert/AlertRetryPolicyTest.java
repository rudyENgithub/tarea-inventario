package com.store.inventory.application.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlertRetryPolicyTest {

    @Test
    void validatesItsArguments() {
        assertThatThrownBy(() -> new AlertRetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertRetryPolicy(1, Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertRetryPolicy(1, Duration.ofSeconds(-1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertRetryPolicy(1, Duration.ofSeconds(10), Duration.ofSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RNF-5: exponential backoff 10s, 20s, 40s... then no more attempts")
    void exponentialBackoff() {
        var policy = new AlertRetryPolicy(4, Duration.ofSeconds(10), Duration.ofHours(1));

        assertThat(policy.delayAfter(1)).contains(Duration.ofSeconds(10));
        assertThat(policy.delayAfter(2)).contains(Duration.ofSeconds(20));
        assertThat(policy.delayAfter(3)).contains(Duration.ofSeconds(40));
        assertThat(policy.delayAfter(4)).isEmpty();
        assertThat(policy.delayAfter(5)).isEmpty();
    }

    @Test
    void delayIsCappedAtMaxDelay() {
        var policy = new AlertRetryPolicy(10, Duration.ofSeconds(10), Duration.ofSeconds(25));

        assertThat(policy.delayAfter(1)).contains(Duration.ofSeconds(10));
        assertThat(policy.delayAfter(2)).contains(Duration.ofSeconds(20));
        assertThat(policy.delayAfter(3)).contains(Duration.ofSeconds(25));
        assertThat(policy.delayAfter(9)).contains(Duration.ofSeconds(25));
    }

    @Test
    void doesNotOverflowWithHugeAttemptCounts() {
        var policy = new AlertRetryPolicy(200, Duration.ofSeconds(10), Duration.ofMinutes(15));

        assertThat(policy.delayAfter(100)).contains(Duration.ofMinutes(15));
        assertThat(policy.delayAfter(199)).contains(Duration.ofMinutes(15));
        assertThat(policy.delayAfter(200)).isEmpty();
    }

    @Test
    void presets() {
        assertThat(AlertRetryPolicy.noRetry().delayAfter(1)).isEmpty();
        assertThat(AlertRetryPolicy.defaults())
                .isEqualTo(new AlertRetryPolicy(5, Duration.ofSeconds(30), Duration.ofMinutes(15)));
    }

    @Test
    void rejectsNonPositiveAttempts() {
        assertThatThrownBy(() -> AlertRetryPolicy.defaults().delayAfter(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

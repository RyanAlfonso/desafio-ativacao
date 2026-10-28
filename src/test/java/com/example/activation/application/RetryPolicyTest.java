package com.example.activation.application;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(new DispatchProperties(
            false, Duration.ofSeconds(1), 10, Duration.ofSeconds(30), 4,
            Duration.ofSeconds(2), Duration.ofSeconds(60)));

    @Test
    void allowsRetryUntilMaxAttempts() {
        assertThat(policy.canRetry(1)).isTrue();
        assertThat(policy.canRetry(3)).isTrue();
        assertThat(policy.canRetry(4)).isFalse();
    }

    @RepeatedTest(20)
    void backoffDoublesWithJitterBetweenHalfAndFull() {
        assertThat(policy.delayBefore(1, null)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(policy.delayBefore(2, null)).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(policy.delayBefore(3, null)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    @Test
    void backoffIsCappedByMaxBackoff() {
        assertThat(policy.delayBefore(30, null)).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    @Test
    void retryAfterFromProviderWinsButIsCapped() {
        assertThat(policy.delayBefore(1, Duration.ofSeconds(45))).isEqualTo(Duration.ofSeconds(45));
        assertThat(policy.delayBefore(1, Duration.ofHours(1))).isEqualTo(Duration.ofSeconds(60));
    }
}

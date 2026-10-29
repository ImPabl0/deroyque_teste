package br.teste.deroyque.services;

import br.teste.deroyque.config.ActivationProperties;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(
            new ActivationProperties(3, Duration.ofSeconds(2), Duration.ofSeconds(30), 10, Duration.ofMinutes(2)));

    @Test
    void allowsRetryUntilMaxAttempts() {
        assertThat(policy.canRetry(1)).isTrue();
        assertThat(policy.canRetry(2)).isTrue();
        assertThat(policy.canRetry(3)).isFalse();
    }

    @RepeatedTest(20)
    void delayGrowsExponentiallyWithJitter() {
        assertThat(policy.delayAfter(1, null)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(policy.delayAfter(2, null)).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(policy.delayAfter(3, null)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    @RepeatedTest(20)
    void delayIsCapped() {
        assertThat(policy.delayAfter(50, null)).isBetween(Duration.ofSeconds(15), Duration.ofSeconds(30));
    }

    @Test
    void honorsRetryAfterWhenLongerThanBackoff() {
        assertThat(policy.delayAfter(1, Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.delayAfter(3, Duration.ofMillis(1))).isGreaterThanOrEqualTo(Duration.ofSeconds(4));
    }
}

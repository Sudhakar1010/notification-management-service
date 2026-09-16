package com.nms.delivery;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryBackoffPolicyTest {

    @Test
    void delayDoublesWithEachAttempt() {
        RetryBackoffPolicy policy = new RetryBackoffPolicy(1000, 30000);

        assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(1000));
        assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofMillis(2000));
        assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofMillis(4000));
    }

    @Test
    void delayIsCappedAtMaxDelay() {
        RetryBackoffPolicy policy = new RetryBackoffPolicy(1000, 3000);

        assertThat(policy.nextDelay(5)).isEqualTo(Duration.ofMillis(3000));
    }

    @Test
    void firstAttemptCountUsesBaseDelay() {
        RetryBackoffPolicy policy = new RetryBackoffPolicy(500, 30000);

        assertThat(policy.nextDelay(0)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(500));
    }
}

package com.nms.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Exponential backoff for retryable delivery failures: delay doubles with
 * each attempt (base * 2^(attemptCount-1)), capped at maxDelay. See
 * ARCHITECTURE.md ADR-011.
 */
@Component
public class RetryBackoffPolicy {

    private final long baseDelayMillis;
    private final long maxDelayMillis;

    public RetryBackoffPolicy(
            @Value("${notification.delivery.retry-base-delay-ms:2000}") long baseDelayMillis,
            @Value("${notification.delivery.retry-max-delay-ms:30000}") long maxDelayMillis) {
        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    /**
     * @param attemptCount the number of attempts already made (1 after the first failed try)
     */
    public Duration nextDelay(int attemptCount) {
        int exponent = Math.max(0, attemptCount - 1);
        long uncapped = baseDelayMillis * (1L << Math.min(exponent, 20));
        return Duration.ofMillis(Math.min(uncapped, maxDelayMillis));
    }
}

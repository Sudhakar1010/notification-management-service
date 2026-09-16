package com.nms.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Resilience4j configuration for the webhook channel specifically -- Email
 * and SMS are in-process simulations with no real I/O to protect, so
 * neither retry nor a circuit breaker applies to them. See ARCHITECTURE.md
 * ADR-016.
 *
 * Composition order (see WebhookChannelProvider): Retry wraps CircuitBreaker,
 * so each retry attempt individually respects the breaker's state -- once a
 * host's circuit opens, further attempts (retries included) fail fast
 * instead of waiting out a timeout.
 */
@Configuration
public class WebhookResilienceConfig {

    /**
     * One registry, one CircuitBreaker instance per target authority
     * (host:port -- see WebhookChannelProvider) -- webhook targets are
     * arbitrary caller-supplied URLs, so one bad target must not trip the
     * breaker for every other target. 4xx responses are ignored entirely
     * (neither failure nor success): they signal a bad request, not an
     * unhealthy target, and must not count toward opening the circuit.
     */
    @Bean
    public CircuitBreakerRegistry webhookCircuitBreakerRegistry(
            @Value("${notification.delivery.webhook.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${notification.delivery.webhook.circuit-breaker.sliding-window-size:10}") int slidingWindowSize,
            @Value("${notification.delivery.webhook.circuit-breaker.minimum-number-of-calls:5}") int minimumNumberOfCalls,
            @Value("${notification.delivery.webhook.circuit-breaker.wait-duration-in-open-state-seconds:30}") long waitDurationInOpenStateSeconds) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationInOpenStateSeconds))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(HttpClientErrorException.class)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    /**
     * A single shared Retry -- unlike the circuit breaker, Retry holds no
     * per-target state (just per-call attempt counting), so one instance is
     * safe to share across every host. Deliberately narrow and fast: only
     * retries genuinely transient conditions (5xx, connection-level issues)
     * with a short fixed delay, as a smoothing layer under the slower,
     * durable, audited retry loop DeliveryAttemptProcessor already runs
     * across DeliveryAttempt rows (ADR-011) -- this is not a replacement
     * for that, it's one layer beneath it. Explicitly excludes read
     * timeouts (already slow; retrying fast just makes it slower) and 4xx
     * (never recoverable by retrying).
     */
    @Bean
    public Retry webhookRetry(
            @Value("${notification.delivery.webhook.retry.max-attempts:2}") int maxAttempts,
            @Value("${notification.delivery.webhook.retry.wait-duration-ms:200}") long waitDurationMillis) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(waitDurationMillis))
                .retryOnException(throwable -> throwable instanceof HttpServerErrorException
                        || (throwable instanceof ResourceAccessException resourceAccessException
                                && !(resourceAccessException.getCause() instanceof SocketTimeoutException)))
                .build();
        return Retry.of("webhook", config);
    }
}

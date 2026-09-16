package com.nms.delivery;

import com.nms.audit.AuditService;
import com.nms.common.AuditEventType;
import com.nms.common.DeliveryStatus;
import com.nms.common.FailureType;
import com.nms.notification.Notification;
import com.nms.notification.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Processes exactly one DeliveryAttempt row per call, in its own transaction
 * (REQUIRES_NEW) so that one bad attempt can't roll back the others in the
 * same poll batch. Each row is claimed by flipping it to SENDING under
 * optimistic locking (see DeliveryAttempt#version) before any provider call
 * is made -- if another poller thread already claimed the row, the flush
 * below throws and this call is a no-op, satisfying the "reprocessing a
 * queued delivery must not create uncontrolled duplicate side effects"
 * requirement. A retryable failure (see FailureType#isRetryable) reuses
 * this same row for its next attempt via RETRY_SCHEDULED -- retries never
 * create a second DeliveryAttempt row -- until maxAttempts is reached, at
 * which point the row moves to EXHAUSTED. See ARCHITECTURE.md ADR-011.
 */
@Service
public class DeliveryAttemptProcessor {

    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final NotificationRepository notificationRepository;
    private final ChannelProviderRegistry providerRegistry;
    private final AuditService auditService;
    private final RetryBackoffPolicy retryBackoffPolicy;

    public DeliveryAttemptProcessor(DeliveryAttemptRepository deliveryAttemptRepository,
                                     NotificationRepository notificationRepository,
                                     ChannelProviderRegistry providerRegistry,
                                     AuditService auditService,
                                     RetryBackoffPolicy retryBackoffPolicy) {
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.notificationRepository = notificationRepository;
        this.providerRegistry = providerRegistry;
        this.auditService = auditService;
        this.retryBackoffPolicy = retryBackoffPolicy;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(UUID attemptId) {
        DeliveryAttempt attempt = deliveryAttemptRepository.findById(attemptId).orElse(null);
        if (attempt == null || (attempt.getStatus() != DeliveryStatus.QUEUED
                && attempt.getStatus() != DeliveryStatus.RETRY_SCHEDULED)) {
            return;
        }

        attempt.setStatus(DeliveryStatus.SENDING);
        attempt.setUpdatedAt(Instant.now());
        attempt = deliveryAttemptRepository.saveAndFlush(attempt);

        Notification notification = notificationRepository.findById(attempt.getNotificationId()).orElse(null);
        if (notification == null) {
            attempt.setStatus(DeliveryStatus.FAILED);
            attempt.setLastFailureType(FailureType.PERMANENT_PROVIDER_REJECTION);
            attempt.setLastFailureReason("Parent notification no longer exists");
            deliveryAttemptRepository.save(attempt);
            return;
        }

        if (notification.getExpiresAt() != null && Instant.now().isAfter(notification.getExpiresAt())) {
            attempt.setStatus(DeliveryStatus.SKIPPED);
            attempt.setUpdatedAt(Instant.now());
            deliveryAttemptRepository.save(attempt);
            auditService.record(notification.getId(), AuditEventType.NOTIFICATION_EXPIRED,
                    "recipient=%s, channel=%s skipped: expiresAt=%s already passed"
                            .formatted(attempt.getRecipientId(), attempt.getChannel(), notification.getExpiresAt()));
            return;
        }

        ChannelProvider provider = providerRegistry.find(attempt.getChannel()).orElse(null);
        attempt.setAttemptCount(attempt.getAttemptCount() + 1);

        if (provider == null) {
            attempt.setStatus(DeliveryStatus.FAILED);
            attempt.setLastFailureType(FailureType.PERMANENT_PROVIDER_REJECTION);
            attempt.setLastFailureReason("No provider registered for channel " + attempt.getChannel());
            attempt.setUpdatedAt(Instant.now());
            deliveryAttemptRepository.save(attempt);
            auditService.record(notification.getId(), AuditEventType.DELIVERY_FAILED,
                    "recipient=%s, channel=%s, reason=no provider registered"
                            .formatted(attempt.getRecipientId(), attempt.getChannel()));
            return;
        }

        DeliveryContext context = new DeliveryContext(notification.getId(), attempt.getRecipientId(),
                attempt.getChannel(), notification.getSubject(), notification.getMessage(),
                notification.getSeverity(), notification.getPriority());

        ProviderResult result = provider.send(context);
        auditService.record(notification.getId(), AuditEventType.DELIVERY_ATTEMPTED,
                "recipient=%s, channel=%s, attempt=%d/%d"
                        .formatted(attempt.getRecipientId(), attempt.getChannel(), attempt.getAttemptCount(), attempt.getMaxAttempts()));

        if (result.success()) {
            attempt.setStatus(DeliveryStatus.SUCCEEDED);
            attempt.setSentAt(Instant.now());
            attempt.setLastFailureType(FailureType.NONE);
            attempt.setLastFailureReason(null);
            auditService.record(notification.getId(), AuditEventType.DELIVERY_SUCCEEDED,
                    "recipient=%s, channel=%s".formatted(attempt.getRecipientId(), attempt.getChannel()));
        } else {
            attempt.setLastFailureType(result.failureType());
            attempt.setLastFailureReason(result.message());

            boolean retryable = result.failureType().isRetryable();
            boolean attemptsRemaining = attempt.getAttemptCount() < attempt.getMaxAttempts();

            if (retryable && attemptsRemaining) {
                Duration delay = retryBackoffPolicy.nextDelay(attempt.getAttemptCount());
                attempt.setStatus(DeliveryStatus.RETRY_SCHEDULED);
                attempt.setNextAttemptAt(Instant.now().plus(delay));
                auditService.record(notification.getId(), AuditEventType.RETRY_SCHEDULED,
                        "recipient=%s, channel=%s, failureType=%s, nextAttemptInMs=%d, attempt=%d/%d"
                                .formatted(attempt.getRecipientId(), attempt.getChannel(), result.failureType(),
                                        delay.toMillis(), attempt.getAttemptCount(), attempt.getMaxAttempts()));
            } else if (retryable) {
                attempt.setStatus(DeliveryStatus.EXHAUSTED);
                auditService.record(notification.getId(), AuditEventType.DELIVERY_EXHAUSTED,
                        "recipient=%s, channel=%s, failureType=%s, reason=%s, attempts=%d/%d"
                                .formatted(attempt.getRecipientId(), attempt.getChannel(), result.failureType(),
                                        result.message(), attempt.getAttemptCount(), attempt.getMaxAttempts()));
            } else {
                attempt.setStatus(DeliveryStatus.FAILED);
                auditService.record(notification.getId(), AuditEventType.DELIVERY_FAILED,
                        "recipient=%s, channel=%s, failureType=%s, reason=%s"
                                .formatted(attempt.getRecipientId(), attempt.getChannel(), result.failureType(), result.message()));
            }
        }

        attempt.setUpdatedAt(Instant.now());
        deliveryAttemptRepository.save(attempt);
    }
}

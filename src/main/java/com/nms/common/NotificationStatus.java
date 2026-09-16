package com.nms.common;

/**
 * Notification-level lifecycle. Documented state model (see requirement 4.2):
 *
 * RECEIVED -> ROUTED -> PROCESSING -> DELIVERED
 *                                   -> PARTIALLY_DELIVERED
 *                                   -> FAILED
 * RECEIVED -> REJECTED   (failed request validation)
 * ROUTED   -> EXPIRED    (expiresAt reached before any successful delivery)
 *
 * Overall status is derived from the aggregate of the notification's
 * DeliveryAttempt rows (see NotificationStatusCalculator) rather than
 * tracked independently, so it can never drift from the underlying facts.
 */
public enum NotificationStatus {
    RECEIVED,
    ROUTED,
    PROCESSING,
    DELIVERED,
    PARTIALLY_DELIVERED,
    FAILED,
    REJECTED,
    EXPIRED
}

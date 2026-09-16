package com.nms.notification;

import com.nms.common.DeliveryStatus;
import com.nms.common.NotificationStatus;
import com.nms.delivery.DeliveryAttempt;

import java.util.List;
import java.util.Set;

/**
 * Derives the notification-level status purely from its DeliveryAttempt rows
 * so the two can never drift apart. Documented derivation rule:
 *
 * - any attempt still in flight (QUEUED/SENDING/RETRY_SCHEDULED)  -> PROCESSING
 * - zero deliverable units (no attempts, e.g. nobody had an eligible
 *   channel) or zero successes                                    -> FAILED
 * - every deliverable unit succeeded                               -> DELIVERED
 * - a mix of success and terminal failure                          -> PARTIALLY_DELIVERED
 *
 * `recipientsWithNoRoute` counts recipients for whom routing produced zero
 * eligible channels (so no DeliveryAttempt row exists for them at all) --
 * they still count as a failed delivery unit for the aggregate.
 */
public final class NotificationStatusCalculator {

    private static final Set<DeliveryStatus> IN_FLIGHT = Set.of(
            DeliveryStatus.QUEUED, DeliveryStatus.SENDING, DeliveryStatus.RETRY_SCHEDULED);

    private NotificationStatusCalculator() {
    }

    public static NotificationStatus compute(List<DeliveryAttempt> attempts, long recipientsWithNoRoute) {
        boolean anyInFlight = attempts.stream().anyMatch(a -> IN_FLIGHT.contains(a.getStatus()));
        if (anyInFlight) {
            return NotificationStatus.PROCESSING;
        }

        long total = attempts.size() + recipientsWithNoRoute;
        if (total == 0) {
            return NotificationStatus.FAILED;
        }

        long succeeded = attempts.stream().filter(a -> a.getStatus() == DeliveryStatus.SUCCEEDED).count();
        if (succeeded == 0) {
            return NotificationStatus.FAILED;
        }
        if (succeeded == total) {
            return NotificationStatus.DELIVERED;
        }
        return NotificationStatus.PARTIALLY_DELIVERED;
    }
}

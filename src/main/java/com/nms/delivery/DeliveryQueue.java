package com.nms.delivery;

import com.nms.common.Channel;

import java.time.Instant;
import java.util.UUID;

/**
 * Port the notification module depends on to enqueue a delivery attempt.
 * Keeps notification from depending on delivery's entity/repository shape --
 * a DeliveryAttempt is delivery's aggregate, not notification's, so
 * notification only gets to ask for one to be created, not construct one
 * itself.
 */
public interface DeliveryQueue {

    void enqueue(UUID notificationId, String recipientId, Channel channel, Instant firstAttemptAt);
}

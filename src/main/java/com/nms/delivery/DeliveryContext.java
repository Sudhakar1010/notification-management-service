package com.nms.delivery;

import com.nms.common.Channel;
import com.nms.common.Priority;
import com.nms.common.Severity;

import java.util.UUID;

/**
 * Decoupled view of what a provider needs to attempt a send -- providers
 * never see the JPA entities directly.
 */
public record DeliveryContext(
        UUID notificationId,
        String recipientId,
        Channel channel,
        String subject,
        String message,
        Severity severity,
        Priority priority
) {
}

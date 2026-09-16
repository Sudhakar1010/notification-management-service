package com.nms.delivery;

import com.nms.common.Priority;
import com.nms.common.Severity;

import java.util.UUID;

/**
 * Decoupled view of what a provider needs to attempt a send -- providers
 * never see the JPA entities directly. No `channel` field: each
 * ChannelProvider already knows its own channel via supportedChannel(),
 * so passing it again here would be redundant, unread data.
 */
public record DeliveryContext(
        UUID notificationId,
        String recipientId,
        String subject,
        String message,
        Severity severity,
        Priority priority
) {
}

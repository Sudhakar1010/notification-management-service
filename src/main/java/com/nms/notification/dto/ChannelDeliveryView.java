package com.nms.notification.dto;

import com.nms.common.Channel;
import com.nms.common.DeliveryStatus;
import com.nms.common.FailureType;

import java.time.Instant;

public record ChannelDeliveryView(
        Channel channel,
        DeliveryStatus status,
        int attemptCount,
        int maxAttempts,
        FailureType lastFailureType,
        String lastFailureReason,
        Instant nextAttemptAt,
        Instant sentAt
) {
}

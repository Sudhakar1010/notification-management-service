package com.nms.notification.dto;

import com.nms.common.Channel;
import com.nms.common.NotificationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationStatusResponse(
        UUID notificationId,
        NotificationStatus overallStatus,
        List<Channel> requestedChannels,
        List<RecipientDeliveryView> recipients,
        Instant createdAt,
        Instant updatedAt
) {
}

package com.nms.notification.dto;

import com.nms.common.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID notificationId,
        NotificationStatus status,
        boolean duplicate,
        Instant createdAt
) {
}

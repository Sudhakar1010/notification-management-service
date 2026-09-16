package com.nms.notification.dto;

import com.nms.common.Channel;
import com.nms.common.NotificationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * `requestedChannels` echoes what the caller asked for; `selectedChannels`
 * is what routing actually decided across all recipients (ADR-010 may make
 * these differ per recipient -- an opt-out, or a CRITICAL override, can
 * both change the routed set relative to the request). See requirement
 * 4.2, which lists these as two distinct status fields.
 */
public record NotificationStatusResponse(
        UUID notificationId,
        NotificationStatus overallStatus,
        List<Channel> requestedChannels,
        List<Channel> selectedChannels,
        List<RecipientDeliveryView> recipients,
        Instant createdAt,
        Instant updatedAt
) {
}

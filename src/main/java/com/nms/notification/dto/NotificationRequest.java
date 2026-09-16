package com.nms.notification.dto;

import com.nms.common.Channel;
import com.nms.common.Priority;
import com.nms.common.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record NotificationRequest(

        @NotBlank(message = "idempotencyKey is required")
        String idempotencyKey,

        @NotBlank(message = "sourceSystem is required")
        String sourceSystem,

        @NotBlank(message = "eventId is required")
        String eventId,

        @NotBlank(message = "notificationType is required")
        String notificationType,

        @NotNull(message = "severity is required")
        Severity severity,

        @NotNull(message = "priority is required")
        Priority priority,

        String subject,

        @NotBlank(message = "message is required")
        String message,

        @NotEmpty(message = "at least one recipient is required")
        List<@Valid RecipientRequest> recipients,

        @NotEmpty(message = "at least one requested channel is required")
        List<Channel> requestedChannels,

        Instant scheduledAt,

        Instant expiresAt
) {
}

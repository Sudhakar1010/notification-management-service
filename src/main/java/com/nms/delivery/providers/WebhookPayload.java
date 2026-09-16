package com.nms.delivery.providers;

import com.nms.common.Priority;
import com.nms.common.Severity;

import java.util.UUID;

/** Body posted to a webhook target -- deliberately excludes recipient PII. */
public record WebhookPayload(
        UUID notificationId,
        String subject,
        String message,
        Severity severity,
        Priority priority
) {
}

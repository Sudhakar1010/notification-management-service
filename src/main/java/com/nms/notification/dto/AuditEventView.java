package com.nms.notification.dto;

import com.nms.common.AuditEventType;

import java.time.Instant;

public record AuditEventView(
        AuditEventType eventType,
        String detail,
        Instant timestamp
) {
}

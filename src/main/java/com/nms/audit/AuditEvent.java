package com.nms.audit;

import com.nms.common.AuditEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit trail row. `detail` must only ever hold short factual summaries
 * (e.g. "requested=[EMAIL], selected=[EMAIL]") -- never the notification
 * message body, recipient PII beyond the opaque recipientId already used
 * elsewhere, or provider credentials. See requirement 4.9.
 */
@Entity
@Table(name = "audit_event")
@Getter
@Setter
public class AuditEvent {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private AuditEventType eventType;

    @Column(length = 500)
    private String detail;

    @Column(nullable = false)
    private Instant timestamp = Instant.now();
}

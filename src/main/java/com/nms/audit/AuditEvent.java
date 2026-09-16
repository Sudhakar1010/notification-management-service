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
 *
 * `notificationId` is nullable: a NOTIFICATION_REJECTED event fires when a
 * request fails validation before a Notification row (and its id) exists
 * at all, so there is nothing to attach it to. These rows are recorded for
 * completeness but are not retrievable via the per-notification audit
 * endpoint, since by definition no notification was ever created.
 */
@Entity
@Table(name = "audit_event")
@Getter
@Setter
public class AuditEvent {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "notification_id")
    private UUID notificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private AuditEventType eventType;

    @Column(length = 500)
    private String detail;

    @Column(nullable = false)
    private Instant timestamp = Instant.now();
}

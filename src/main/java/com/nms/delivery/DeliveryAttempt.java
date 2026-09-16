package com.nms.delivery;

import com.nms.common.Channel;
import com.nms.common.DeliveryStatus;
import com.nms.common.FailureType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One outbox row per (notification, recipient, channel) -- this triple is
 * also the delivery-level dedup boundary: the unique constraint below
 * guarantees routing can be re-run safely without ever creating a second
 * delivery attempt for the same recipient+channel combination, and the
 * optimistic @Version lock guarantees the scheduled worker can't process
 * the same QUEUED row twice concurrently.
 */
@Entity
@Table(name = "delivery_attempt", uniqueConstraints = @UniqueConstraint(
        name = "uk_delivery_dedup_boundary", columnNames = {"notification_id", "recipient_id", "channel"}))
@Getter
@Setter
public class DeliveryAttempt {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "recipient_id", nullable = false)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status = DeliveryStatus.QUEUED;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "last_failure_type")
    private FailureType lastFailureType = FailureType.NONE;

    @Column(name = "last_failure_reason", length = 500)
    private String lastFailureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Version
    private Long version;
}

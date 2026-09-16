package com.nms.notification;

import com.nms.common.Channel;
import com.nms.common.NotificationStatus;
import com.nms.common.Priority;
import com.nms.common.Severity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The dedup boundary for idempotent submission is the (sourceSystem, idempotencyKey)
 * pair -- see NotificationService#submit. A notification that expires before any
 * successful delivery moves to EXPIRED rather than being deleted, so it remains
 * visible via the status/audit APIs.
 */
@Entity
@Table(name = "notification", uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_dedup_boundary", columnNames = {"source_system", "idempotency_key"}))
@Getter
@Setter
public class Notification {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "source_system", nullable = false)
    private String sourceSystem;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "notification_type", nullable = false)
    private String notificationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    private String subject;

    @Column(nullable = false, length = 2000)
    private String message;

    @ElementCollection(targetClass = Channel.class, fetch = FetchType.EAGER)
    @CollectionTable(name = "notification_requested_channel", joinColumns = @JoinColumn(name = "notification_id"))
    @Column(name = "channel")
    @Enumerated(EnumType.STRING)
    @OrderColumn(name = "position")
    private List<Channel> requestedChannels = new ArrayList<>();

    @OneToMany(mappedBy = "notification", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<NotificationRecipient> recipients = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status = NotificationStatus.RECEIVED;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Version
    private Long version;

    public void addRecipient(NotificationRecipient recipient) {
        recipient.setNotification(this);
        this.recipients.add(recipient);
    }
}

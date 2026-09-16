package com.nms.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "notification_recipient")
@Getter
@Setter
public class NotificationRecipient {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    /**
     * Address/identifier of the recipient (e.g. email address, phone number,
     * user id) -- interpretation is channel-dependent and resolved by the
     * ChannelProvider, not by this entity.
     */
    @Column(name = "recipient_id", nullable = false)
    private String recipientId;
}

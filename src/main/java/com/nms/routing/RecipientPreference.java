package com.nms.routing;

import com.nms.common.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * A recipient's channel opt-out. Absence of a row for (recipientId, channel)
 * means the recipient is opted in by default -- this table only needs to
 * record exceptions, which keeps the demo seed data small and realistic.
 */
@Entity
@Table(name = "recipient_preference", uniqueConstraints = @UniqueConstraint(
        name = "uk_recipient_channel", columnNames = {"recipient_id", "channel"}))
@Getter
@Setter
public class RecipientPreference {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "recipient_id", nullable = false)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(name = "opted_in", nullable = false)
    private boolean optedIn = false;
}

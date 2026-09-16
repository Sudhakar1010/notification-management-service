package com.nms.delivery;

import com.nms.common.Channel;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class DeliveryAttemptQueue implements DeliveryQueue {

    private final DeliveryAttemptRepository deliveryAttemptRepository;

    public DeliveryAttemptQueue(DeliveryAttemptRepository deliveryAttemptRepository) {
        this.deliveryAttemptRepository = deliveryAttemptRepository;
    }

    @Override
    public void enqueue(UUID notificationId, String recipientId, Channel channel, Instant firstAttemptAt) {
        DeliveryAttempt attempt = new DeliveryAttempt();
        attempt.setNotificationId(notificationId);
        attempt.setRecipientId(recipientId);
        attempt.setChannel(channel);
        attempt.setNextAttemptAt(firstAttemptAt);
        deliveryAttemptRepository.save(attempt);
    }
}

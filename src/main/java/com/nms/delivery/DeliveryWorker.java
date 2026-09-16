package com.nms.delivery;

import com.nms.common.DeliveryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Polls the delivery_attempt outbox for due rows and hands each one to
 * DeliveryAttemptProcessor. This is the "asynchronous processing" stage:
 * notification submission (NotificationService) returns as soon as
 * DeliveryAttempt rows are queued -- actual provider dispatch always
 * happens here, off the request thread, on the next poll tick.
 */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private static final List<DeliveryStatus> DUE_STATUSES = List.of(DeliveryStatus.QUEUED, DeliveryStatus.RETRY_SCHEDULED);

    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final DeliveryAttemptProcessor processor;

    public DeliveryWorker(DeliveryAttemptRepository deliveryAttemptRepository, DeliveryAttemptProcessor processor) {
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${notification.delivery.poll-interval-ms:2000}")
    public void poll() {
        List<DeliveryAttempt> due = deliveryAttemptRepository
                .findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(DUE_STATUSES, Instant.now());

        for (DeliveryAttempt attempt : due) {
            try {
                processor.process(attempt.getId());
            } catch (ObjectOptimisticLockingFailureException e) {
                log.debug("Delivery attempt {} was already claimed by another worker tick, skipping", attempt.getId());
            }
        }
    }
}

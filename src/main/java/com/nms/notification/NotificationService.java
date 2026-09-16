package com.nms.notification;

import com.nms.audit.AuditService;
import com.nms.common.AuditEventType;
import com.nms.common.Channel;
import com.nms.common.NotificationStatus;
import com.nms.delivery.DeliveryAttempt;
import com.nms.delivery.DeliveryAttemptRepository;
import com.nms.delivery.DeliveryQueue;
import com.nms.exception.IdempotencyKeyConflictException;
import com.nms.exception.InvalidNotificationRequestException;
import com.nms.exception.NotificationNotFoundException;
import com.nms.notification.dto.AuditEventView;
import com.nms.notification.dto.ChannelDeliveryView;
import com.nms.notification.dto.NotificationRequest;
import com.nms.notification.dto.NotificationResponse;
import com.nms.notification.dto.NotificationStatusResponse;
import com.nms.notification.dto.RecipientDeliveryView;
import com.nms.routing.RoutingDecision;
import com.nms.routing.RoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nms.audit.AuditEventRepository;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    // Read side: querying delivery attempts to project a status view is a
    // cross-module read, which stays a direct repository query (no port
    // needed -- it doesn't touch delivery's aggregate). Writing a new
    // DeliveryAttempt goes through the DeliveryQueue port below instead,
    // since that entity belongs to the delivery module.
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final DeliveryQueue deliveryQueue;
    private final AuditEventRepository auditEventRepository;
    private final RoutingService routingService;
    private final AuditService auditService;

    public NotificationService(NotificationRepository notificationRepository,
                                DeliveryAttemptRepository deliveryAttemptRepository,
                                DeliveryQueue deliveryQueue,
                                AuditEventRepository auditEventRepository,
                                RoutingService routingService,
                                AuditService auditService) {
        this.notificationRepository = notificationRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.deliveryQueue = deliveryQueue;
        this.auditEventRepository = auditEventRepository;
        this.routingService = routingService;
        this.auditService = auditService;
    }

    @Transactional
    public NotificationResponse submit(NotificationRequest request) {
        validate(request);

        return notificationRepository.findBySourceSystemAndIdempotencyKey(request.sourceSystem(), request.idempotencyKey())
                .map(existing -> replayDuplicate(existing, request))
                .orElseGet(() -> createAndRoute(request));
    }

    private void validate(NotificationRequest request) {
        if (request.expiresAt() != null && request.scheduledAt() != null
                && !request.expiresAt().isAfter(request.scheduledAt())) {
            rejectAndThrow(request, "expiresAt must be after scheduledAt");
        }
        if (request.expiresAt() != null && request.expiresAt().isBefore(Instant.now())) {
            rejectAndThrow(request, "expiresAt must be in the future");
        }

        Set<String> seenRecipients = new HashSet<>();
        for (var recipient : request.recipients()) {
            if (!seenRecipients.add(recipient.recipientId())) {
                rejectAndThrow(request, "duplicate recipientId in request: " + recipient.recipientId());
            }
        }
    }

    private void rejectAndThrow(NotificationRequest request, String reason) {
        auditService.recordIndependently(null, AuditEventType.NOTIFICATION_REJECTED,
                "sourceSystem=%s, idempotencyKey=%s, reason=%s"
                        .formatted(request.sourceSystem(), request.idempotencyKey(), reason));
        throw new InvalidNotificationRequestException(reason);
    }

    private NotificationResponse replayDuplicate(Notification existing, NotificationRequest request) {
        String incomingFingerprint = RequestFingerprint.of(request);
        if (!incomingFingerprint.equals(existing.getRequestFingerprint())) {
            auditService.recordIndependently(existing.getId(), AuditEventType.IDEMPOTENCY_KEY_CONFLICT,
                    "idempotencyKey=%s reused by sourceSystem=%s with a different payload than the original submission"
                            .formatted(request.idempotencyKey(), request.sourceSystem()));
            throw new IdempotencyKeyConflictException(
                    "idempotencyKey '%s' was already used by sourceSystem '%s' with a different request payload"
                            .formatted(request.idempotencyKey(), request.sourceSystem()));
        }

        auditService.record(existing.getId(), AuditEventType.NOTIFICATION_DEDUPLICATED,
                "idempotencyKey=%s reused by sourceSystem=%s; no new notification created"
                        .formatted(request.idempotencyKey(), request.sourceSystem()));
        return new NotificationResponse(existing.getId(), existing.getStatus(), true, existing.getCreatedAt());
    }

    private NotificationResponse createAndRoute(NotificationRequest request) {
        Notification notification = new Notification();
        notification.setSourceSystem(request.sourceSystem());
        notification.setEventId(request.eventId());
        notification.setIdempotencyKey(request.idempotencyKey());
        notification.setRequestFingerprint(RequestFingerprint.of(request));
        notification.setNotificationType(request.notificationType());
        notification.setSeverity(request.severity());
        notification.setPriority(request.priority());
        notification.setSubject(request.subject());
        notification.setMessage(request.message());
        notification.setRequestedChannels(new ArrayList<>(request.requestedChannels()));
        notification.setScheduledAt(request.scheduledAt());
        notification.setExpiresAt(request.expiresAt());
        notification.setStatus(NotificationStatus.RECEIVED);

        for (var recipientRequest : request.recipients()) {
            NotificationRecipient recipient = new NotificationRecipient();
            recipient.setRecipientId(recipientRequest.recipientId());
            notification.addRecipient(recipient);
        }

        notification = notificationRepository.save(notification);
        auditService.record(notification.getId(), AuditEventType.NOTIFICATION_ACCEPTED,
                "sourceSystem=%s, type=%s, severity=%s, recipients=%d"
                        .formatted(notification.getSourceSystem(), notification.getNotificationType(),
                                notification.getSeverity(), notification.getRecipients().size()));

        Instant firstAttemptAt = notification.getScheduledAt() != null ? notification.getScheduledAt() : Instant.now();

        for (NotificationRecipient recipient : notification.getRecipients()) {
            RoutingDecision decision = routingService.decide(recipient.getRecipientId(),
                    notification.getRequestedChannels(), notification.getSeverity());
            auditService.record(notification.getId(), AuditEventType.ROUTING_DECIDED,
                    "recipient=%s, %s".formatted(recipient.getRecipientId(), decision.reason()));

            for (Channel channel : decision.selectedChannels()) {
                deliveryQueue.enqueue(notification.getId(), recipient.getRecipientId(), channel, firstAttemptAt);
                auditService.record(notification.getId(), AuditEventType.DELIVERY_QUEUED,
                        "recipient=%s, channel=%s".formatted(recipient.getRecipientId(), channel));
            }
        }

        notification.setStatus(NotificationStatus.ROUTED);
        notification.setUpdatedAt(Instant.now());
        notification = notificationRepository.save(notification);

        return new NotificationResponse(notification.getId(), notification.getStatus(), false, notification.getCreatedAt());
    }

    @Transactional
    public NotificationStatusResponse getStatus(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByNotificationId(notificationId);

        Map<String, List<DeliveryAttempt>> byRecipient = attempts.stream()
                .collect(Collectors.groupingBy(DeliveryAttempt::getRecipientId));

        long recipientsWithNoRoute = notification.getRecipients().stream()
                .filter(r -> !byRecipient.containsKey(r.getRecipientId()))
                .count();

        NotificationStatus overallStatus = NotificationStatusCalculator.compute(attempts, recipientsWithNoRoute);
        if (overallStatus != notification.getStatus()) {
            notification.setStatus(overallStatus);
            notification.setUpdatedAt(Instant.now());
            // This is an opportunistic refresh (ADR-004), not the source of truth for the
            // response below -- if a concurrent GET for the same notification already wrote
            // the same (or a newer) status, losing this optimistic-lock race is fine to ignore.
            // saveAndFlush forces the version check to happen here, inside the try, rather than
            // silently at commit time after this method has already returned.
            try {
                notificationRepository.saveAndFlush(notification);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.debug("Notification {} status was already refreshed concurrently, skipping", notificationId);
            }
        }

        List<RecipientDeliveryView> recipientViews = notification.getRecipients().stream()
                .map(recipient -> {
                    List<ChannelDeliveryView> channels = byRecipient
                            .getOrDefault(recipient.getRecipientId(), List.of()).stream()
                            .map(a -> new ChannelDeliveryView(a.getChannel(), a.getStatus(), a.getAttemptCount(),
                                    a.getMaxAttempts(), a.getLastFailureType(), a.getLastFailureReason(),
                                    a.getNextAttemptAt(), a.getSentAt()))
                            .toList();
                    return new RecipientDeliveryView(recipient.getRecipientId(), channels);
                })
                .toList();

        List<Channel> selectedChannels = attempts.stream()
                .map(DeliveryAttempt::getChannel)
                .distinct()
                .toList();

        return new NotificationStatusResponse(notification.getId(), overallStatus, notification.getRequestedChannels(),
                selectedChannels, recipientViews, notification.getCreatedAt(), notification.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public List<AuditEventView> getAuditTrail(UUID notificationId) {
        if (!notificationRepository.existsById(notificationId)) {
            throw new NotificationNotFoundException(notificationId);
        }
        return auditEventRepository.findByNotificationIdOrderByTimestampAsc(notificationId).stream()
                .map(e -> new AuditEventView(e.getEventType(), e.getDetail(), e.getTimestamp()))
                .toList();
    }
}

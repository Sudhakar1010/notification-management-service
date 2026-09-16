package com.nms.audit;

import com.nms.common.AuditEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Records an event as part of the caller's current transaction -- if
     * that transaction later rolls back, this event rolls back with it,
     * which is correct when the event describes something that transaction
     * is doing (e.g. NOTIFICATION_ACCEPTED alongside the Notification it
     * describes actually being persisted).
     */
    public void record(UUID notificationId, AuditEventType eventType, String detail) {
        AuditEvent event = new AuditEvent();
        event.setNotificationId(notificationId);
        event.setEventType(eventType);
        event.setDetail(detail);
        repository.save(event);
    }

    /**
     * Records an event in its own transaction, independent of whatever the
     * caller's transaction does next. Use this specifically when the event
     * documents that the caller's current operation is about to fail/abort
     * (e.g. NOTIFICATION_REJECTED) -- otherwise the audit row would be
     * rolled back along with the exception that's about to be thrown,
     * silently losing the very record requirement 4.9 asks for.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(UUID notificationId, AuditEventType eventType, String detail) {
        record(notificationId, eventType, detail);
    }
}

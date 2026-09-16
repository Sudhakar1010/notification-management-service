package com.nms.audit;

import com.nms.common.AuditEventType;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void record(UUID notificationId, AuditEventType eventType, String detail) {
        AuditEvent event = new AuditEvent();
        event.setNotificationId(notificationId);
        event.setEventType(eventType);
        event.setDetail(detail);
        repository.save(event);
    }
}

package com.nms.notification;

import com.nms.notification.dto.AuditEventView;
import com.nms.notification.dto.NotificationRequest;
import com.nms.notification.dto.NotificationResponse;
import com.nms.notification.dto.NotificationStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    public ResponseEntity<NotificationResponse> submit(@Valid @RequestBody NotificationRequest request) {
        NotificationResponse response = notificationService.submit(request);
        HttpStatus status = response.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{id}")
    public NotificationStatusResponse getStatus(@PathVariable UUID id) {
        return notificationService.getStatus(id);
    }

    @GetMapping("/{id}/audit")
    public List<AuditEventView> getAuditTrail(@PathVariable UUID id) {
        return notificationService.getAuditTrail(id);
    }
}

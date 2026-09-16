package com.nms.notification;

import com.nms.exception.SourceSystemMismatchException;
import com.nms.notification.dto.AuditEventView;
import com.nms.notification.dto.NotificationRequest;
import com.nms.notification.dto.NotificationResponse;
import com.nms.notification.dto.NotificationStatusResponse;
import com.nms.security.ApiKeyAuthenticationFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
@Tag(name = "Notifications", description = "Submission, status, and audit trail for notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    @Operation(summary = "Submit a notification",
            description = "Validates, routes, persists, and enqueues delivery for a notification. "
                    + "Returns 202 for a new notification, 200 for an idempotent replay of an "
                    + "identical payload under the same (sourceSystem, idempotencyKey).")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted, delivery processing asynchronously",
                    content = @Content(schema = @Schema(implementation = NotificationResponse.class))),
            @ApiResponse(responseCode = "200", description = "Duplicate of an existing notification (idempotent replay)",
                    content = @Content(schema = @Schema(implementation = NotificationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed or malformed request"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid X-Api-Key (only when authentication is enabled)"),
            @ApiResponse(responseCode = "403", description = "Authenticated caller does not match the request's sourceSystem"),
            @ApiResponse(responseCode = "409", description = "Idempotency key reused with a different payload")
    })
    public ResponseEntity<NotificationResponse> submit(@Valid @RequestBody NotificationRequest request,
                                                         HttpServletRequest httpRequest) {
        Object authenticatedSourceSystem = httpRequest.getAttribute(
                ApiKeyAuthenticationFilter.AUTHENTICATED_SOURCE_SYSTEM_ATTR);
        if (authenticatedSourceSystem != null && !authenticatedSourceSystem.equals(request.sourceSystem())) {
            throw new SourceSystemMismatchException(
                    "Authenticated caller does not match sourceSystem '%s' in the request body"
                            .formatted(request.sourceSystem()));
        }

        NotificationResponse response = notificationService.submit(request);
        HttpStatus status = response.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get notification status",
            description = "Overall status and per-recipient/per-channel delivery status, derived "
                    + "live from delivery attempts.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status found"),
            @ApiResponse(responseCode = "404", description = "Unknown notificationId")
    })
    public NotificationStatusResponse getStatus(@PathVariable UUID id) {
        return notificationService.getStatus(id);
    }

    @GetMapping("/{id}/audit")
    @Operation(summary = "Get audit trail",
            description = "Ordered event log for a notification (accepted, routed, queued, "
                    + "attempted, succeeded/failed, retried, exhausted, deduplicated, expired).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Audit trail found"),
            @ApiResponse(responseCode = "404", description = "Unknown notificationId")
    })
    public List<AuditEventView> getAuditTrail(@PathVariable UUID id) {
        return notificationService.getAuditTrail(id);
    }
}

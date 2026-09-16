package com.nms.notification;

import com.nms.audit.AuditEvent;
import com.nms.audit.AuditEventRepository;
import com.nms.common.AuditEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A request that never becomes a Notification still leaves a
 * NOTIFICATION_REJECTED trace (requirement 4.9), recorded with a null
 * notificationId since none was ever created (see AuditEvent's Javadoc).
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationRejectionAuditTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void beanValidationFailureIsAudited() throws Exception {
        String body = """
                {"idempotencyKey":"reject-bean-%s","sourceSystem":"integration-test",
                 "eventId":"corr-r1","notificationType":"TEST","severity":"INFO","priority":"LOW",
                 "message":"hi","recipients":[],"requestedChannels":[]}
                """.formatted(Instant.now().toEpochMilli());

        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        List<AuditEvent> rejections = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.NOTIFICATION_REJECTED)
                .filter(e -> e.getDetail() != null && e.getDetail().contains("reject-bean-"))
                .toList();

        assertThat(rejections).hasSize(1);
        assertThat(rejections.get(0).getNotificationId()).isNull();
    }

    @Test
    void businessValidationFailureIsAudited() throws Exception {
        String key = "reject-business-" + Instant.now().toEpochMilli();
        String body = """
                {"idempotencyKey":"%s","sourceSystem":"integration-test",
                 "eventId":"corr-r2","notificationType":"TEST","severity":"INFO","priority":"LOW",
                 "message":"hi","recipients":[{"recipientId":"x@example.com"}],"requestedChannels":["EMAIL"],
                 "expiresAt":"2000-01-01T00:00:00Z"}
                """.formatted(key);

        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        List<AuditEvent> rejections = auditEventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AuditEventType.NOTIFICATION_REJECTED)
                .filter(e -> e.getDetail() != null && e.getDetail().contains(key))
                .toList();

        assertThat(rejections).hasSize(1);
        assertThat(rejections.get(0).getNotificationId()).isNull();
        assertThat(rejections.get(0).getDetail()).contains("expiresAt must be in the future");
    }
}

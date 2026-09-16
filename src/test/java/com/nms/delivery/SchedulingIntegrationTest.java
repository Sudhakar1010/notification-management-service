package com.nms.delivery;

import com.nms.notification.Notification;
import com.nms.notification.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.nms.testsupport.NotificationApiTestHelper.pollUntilTerminal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the optional scheduledAt/expiresAt fields (requirement 4.1) are
 * not just stored but actually change delivery behavior.
 */
@SpringBootTest(properties = "notification.delivery.poll-interval-ms=100")
@AutoConfigureMockMvc
class SchedulingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void scheduledAtDelaysDeliveryUntilThatTime() throws Exception {
        Instant scheduledAt = Instant.now().plusMillis(1500);
        String body = """
                {
                  "idempotencyKey": "schedule-it-%s",
                  "sourceSystem": "integration-test",
                  "eventId": "corr-schedule",
                  "notificationType": "TEST",
                  "severity": "INFO",
                  "priority": "LOW",
                  "message": "hello",
                  "recipients": [{"recipientId": "gina@example.com"}],
                  "requestedChannels": ["EMAIL"],
                  "scheduledAt": "%s"
                }
                """.formatted(Instant.now().toEpochMilli(), scheduledAt);

        String submitResponse = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String notificationId = objectMapper.readTree(submitResponse).get("notificationId").asString();

        // Immediately after submit, well before scheduledAt: still queued, not yet attempted.
        String immediateResponse = mockMvc.perform(get("/api/v1/notifications/" + notificationId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode immediateJson = objectMapper.readTree(immediateResponse);
        JsonNode channel = immediateJson.get("recipients").get(0).get("channels").get(0);
        assertThat(channel.get("status").asString()).isEqualTo("QUEUED");
        assertThat(channel.get("attemptCount").asInt()).isEqualTo(0);
        assertThat(Instant.parse(channel.get("nextAttemptAt").asString())).isCloseTo(scheduledAt, within500Ms());

        // Eventually, after scheduledAt passes, the worker picks it up and it succeeds.
        JsonNode finalJson = pollUntilTerminal(mockMvc, objectMapper, notificationId, Duration.ofSeconds(6));
        assertThat(finalJson.get("overallStatus").asString()).isEqualTo("DELIVERED");
    }

    @Test
    void expiresAtBeforeActualProcessingSkipsTheAttempt() throws Exception {
        // scheduledAt is comfortably in the future so the worker structurally
        // cannot claim this row before we backdate expiresAt below -- this is
        // a deterministic setup, not a race against the poll tick's phase.
        // (An earlier version raced a 50ms expiresAt window against the
        // worker's ~0-100ms pickup latency and was genuinely flaky: the
        // worker's tick phase relative to submission is effectively random,
        // so no fixed margin could reliably beat it.)
        Instant scheduledAt = Instant.now().plusMillis(2000);
        String body = """
                {
                  "idempotencyKey": "expire-it-%s",
                  "sourceSystem": "integration-test",
                  "eventId": "corr-expire",
                  "notificationType": "TEST",
                  "severity": "INFO",
                  "priority": "LOW",
                  "message": "hello",
                  "recipients": [{"recipientId": "harold@example.com"}],
                  "requestedChannels": ["EMAIL"],
                  "scheduledAt": "%s"
                }
                """.formatted(Instant.now().toEpochMilli(), scheduledAt);

        String submitResponse = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String notificationId = objectMapper.readTree(submitResponse).get("notificationId").asString();

        // Bypasses submission-time validation (which forbids expiresAt in the
        // past) to deterministically simulate "expired by the time the worker
        // got to it", without racing the worker for real.
        Notification notification = notificationRepository.findById(UUID.fromString(notificationId)).orElseThrow();
        notification.setExpiresAt(Instant.now().minusSeconds(60));
        notificationRepository.save(notification);

        JsonNode statusJson = pollUntilTerminal(mockMvc, objectMapper, notificationId, Duration.ofSeconds(6));
        JsonNode channel = statusJson.get("recipients").get(0).get("channels").get(0);
        assertThat(channel.get("status").asString()).isEqualTo("SKIPPED");

        String audit = mockMvc.perform(get("/api/v1/notifications/" + notificationId + "/audit"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("NOTIFICATION_EXPIRED");
    }

    private static org.assertj.core.data.TemporalUnitOffset within500Ms() {
        return org.assertj.core.api.Assertions.within(500, java.time.temporal.ChronoUnit.MILLIS);
    }
}

package com.nms.routing;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of ADR-010: alice@example.com is seeded (data.sql) as
 * opted out of SMS. A WARNING notification to her should skip SMS; a
 * CRITICAL one should still reach her on SMS, with the override visible in
 * the audit trail.
 */
@SpringBootTest(properties = "notification.delivery.poll-interval-ms=100")
@AutoConfigureMockMvc
class RoutingPrecedenceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void warningSeverityRespectsOptOut() throws Exception {
        JsonNode statusJson = submitAndAwaitTerminal("WARNING", "warn-precedence-" + Instant.now().toEpochMilli());

        JsonNode channels = statusJson.get("recipients").get(0).get("channels");
        assertThat(channels).hasSize(1);
        assertThat(channels.get(0).get("channel").asString()).isEqualTo("EMAIL");
    }

    @Test
    void criticalSeverityOverridesOptOutAndReachesBothChannels() throws Exception {
        String idempotencyKey = "critical-precedence-" + Instant.now().toEpochMilli();
        JsonNode statusJson = submitAndAwaitTerminal("CRITICAL", idempotencyKey);

        JsonNode channels = statusJson.get("recipients").get(0).get("channels");
        assertThat(channels).hasSize(2);
        assertThat(statusJson.get("overallStatus").asString()).isEqualTo("DELIVERED");

        String notificationId = statusJson.get("notificationId").asString();
        String audit = mockMvc.perform(get("/api/v1/notifications/" + notificationId + "/audit"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(audit).contains("severity=CRITICAL overrides opt-out");
    }

    private JsonNode submitAndAwaitTerminal(String severity, String idempotencyKey) throws Exception {
        String body = """
                {
                  "idempotencyKey": "%s",
                  "sourceSystem": "integration-test",
                  "eventId": "corr-precedence",
                  "notificationType": "TEST",
                  "severity": "%s",
                  "priority": "HIGH",
                  "message": "hello",
                  "recipients": [{"recipientId": "alice@example.com"}],
                  "requestedChannels": ["EMAIL", "SMS"]
                }
                """.formatted(idempotencyKey, severity);

        String submitResponse = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String notificationId = objectMapper.readTree(submitResponse).get("notificationId").asString();

        return pollUntilTerminal(notificationId);
    }

    private JsonNode pollUntilTerminal(String notificationId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            String response = mockMvc.perform(get("/api/v1/notifications/" + notificationId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            last = objectMapper.readTree(response);
            if (!last.get("overallStatus").asString().equals("PROCESSING")) {
                return last;
            }
            Thread.sleep(150);
        }
        return last;
    }
}

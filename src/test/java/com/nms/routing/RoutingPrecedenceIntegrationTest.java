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
import java.util.List;

import static com.nms.testsupport.NotificationApiTestHelper.pollUntilTerminal;
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

        List<String> selected = toChannelNames(statusJson.get("selectedChannels"));
        assertThat(selected).containsExactly("EMAIL");
    }

    @Test
    void criticalSeverityOverridesOptOutAndReachesBothChannels() throws Exception {
        String idempotencyKey = "critical-precedence-" + Instant.now().toEpochMilli();
        JsonNode statusJson = submitAndAwaitTerminal("CRITICAL", idempotencyKey);

        JsonNode channels = statusJson.get("recipients").get(0).get("channels");
        assertThat(channels).hasSize(2);
        assertThat(statusJson.get("overallStatus").asString()).isEqualTo("DELIVERED");

        List<String> selected = toChannelNames(statusJson.get("selectedChannels"));
        assertThat(selected).containsExactlyInAnyOrder("EMAIL", "SMS");

        String notificationId = statusJson.get("notificationId").asString();
        String audit = mockMvc.perform(get("/api/v1/notifications/" + notificationId + "/audit"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(audit).contains("severity=CRITICAL overrides opt-out");
    }

    @Test
    void recipientWithNoEligibleChannelHasEmptyChannelsAndFailsOverall() throws Exception {
        String idempotencyKey = "no-eligible-channel-" + Instant.now().toEpochMilli();
        String body = """
                {
                  "idempotencyKey": "%s",
                  "sourceSystem": "integration-test",
                  "eventId": "corr-no-route",
                  "notificationType": "TEST",
                  "severity": "WARNING",
                  "priority": "HIGH",
                  "message": "hello",
                  "recipients": [{"recipientId": "bob@example.com"}],
                  "requestedChannels": ["EMAIL"]
                }
                """.formatted(idempotencyKey);

        String submitResponse = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String notificationId = objectMapper.readTree(submitResponse).get("notificationId").asString();

        JsonNode statusJson = pollUntilTerminal(mockMvc, objectMapper, notificationId, Duration.ofSeconds(5));

        assertThat(statusJson.get("overallStatus").asString()).isEqualTo("FAILED");
        assertThat(statusJson.get("recipients").get(0).get("channels")).isEmpty();
        assertThat(statusJson.get("selectedChannels")).isEmpty();

        String audit = mockMvc.perform(get("/api/v1/notifications/" + notificationId + "/audit"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("selected=[]");
    }

    private static List<String> toChannelNames(JsonNode arrayNode) {
        List<String> names = new java.util.ArrayList<>();
        arrayNode.forEach(n -> names.add(n.asString()));
        return names;
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

        return pollUntilTerminal(mockMvc, objectMapper, notificationId, Duration.ofSeconds(5));
    }
}

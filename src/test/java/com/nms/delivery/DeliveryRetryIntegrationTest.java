package com.nms.delivery;

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

import static com.nms.testsupport.NotificationApiTestHelper.pollUntilTerminal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A "failtransient-" recipient always fails with TRANSIENT_PROVIDER_ERROR
 * (retryable). With fast backoff configured, this proves the full retry
 * loop end-to-end: RETRY_SCHEDULED x2 then EXHAUSTED after maxAttempts (3).
 */
@SpringBootTest(properties = {
        "notification.delivery.poll-interval-ms=100",
        "notification.delivery.retry-base-delay-ms=100",
        "notification.delivery.retry-max-delay-ms=500"
})
@AutoConfigureMockMvc
class DeliveryRetryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void retryableFailureRetriesThenExhausts() throws Exception {
        String body = """
                {
                  "idempotencyKey": "retry-it-%s",
                  "sourceSystem": "integration-test",
                  "eventId": "corr-retry-1",
                  "notificationType": "TEST",
                  "severity": "INFO",
                  "priority": "LOW",
                  "message": "hello",
                  "recipients": [{"recipientId": "failtransient-frank@example.com"}],
                  "requestedChannels": ["EMAIL"]
                }
                """.formatted(Instant.now().toEpochMilli());

        String submitResponse = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String notificationId = objectMapper.readTree(submitResponse).get("notificationId").asString();

        JsonNode statusJson = pollUntilTerminal(mockMvc, objectMapper, notificationId, Duration.ofSeconds(8));

        assertThat(statusJson.get("overallStatus").asString()).isEqualTo("FAILED");
        JsonNode channel = statusJson.get("recipients").get(0).get("channels").get(0);
        assertThat(channel.get("status").asString()).isEqualTo("EXHAUSTED");
        assertThat(channel.get("attemptCount").asInt()).isEqualTo(3);
        assertThat(channel.get("lastFailureType").asString()).isEqualTo("TRANSIENT_PROVIDER_ERROR");

        String audit = mockMvc.perform(get("/api/v1/notifications/" + notificationId + "/audit"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode auditJson = objectMapper.readTree(audit);
        long retryScheduledCount = 0;
        long exhaustedCount = 0;
        for (JsonNode event : auditJson) {
            String type = event.get("eventType").asString();
            if (type.equals("RETRY_SCHEDULED")) retryScheduledCount++;
            if (type.equals("DELIVERY_EXHAUSTED")) exhaustedCount++;
        }
        assertThat(retryScheduledCount).isEqualTo(2);
        assertThat(exhaustedCount).isEqualTo(1);
    }
}

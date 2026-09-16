package com.nms.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end: submit -> async worker picks it up off the request thread ->
 * status settles to a terminal state. Uses a fast poll interval so the test
 * doesn't have to wait for the default 2s production cadence.
 */
@SpringBootTest(properties = "notification.delivery.poll-interval-ms=200")
@AutoConfigureMockMvc
class NotificationFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void submissionIsProcessedAsynchronouslyAndBecomesDelivered() throws Exception {
        String body = """
                {
                  "idempotencyKey": "it-%s",
                  "sourceSystem": "integration-test",
                  "eventId": "corr-it-1",
                  "notificationType": "TEST",
                  "severity": "INFO",
                  "priority": "LOW",
                  "message": "hello",
                  "recipients": [{"recipientId": "dana@example.com"}],
                  "requestedChannels": ["EMAIL"]
                }
                """.formatted(Instant.now().toEpochMilli());

        String submitResponse = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        JsonNode submitJson = objectMapper.readTree(submitResponse);
        assertThat(submitJson.get("status").asString()).isEqualTo("ROUTED");
        String notificationId = submitJson.get("notificationId").asString();

        JsonNode statusJson = pollUntilTerminal(notificationId);

        assertThat(statusJson.get("overallStatus").asString()).isEqualTo("DELIVERED");
        assertThat(statusJson.get("recipients").get(0).get("channels").get(0).get("status").asString())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void repeatingTheSameIdempotencyKeyDoesNotCreateASecondNotification() throws Exception {
        String key = "it-dup-" + Instant.now().toEpochMilli();
        String body = """
                {
                  "idempotencyKey": "%s",
                  "sourceSystem": "integration-test",
                  "eventId": "corr-it-2",
                  "notificationType": "TEST",
                  "severity": "INFO",
                  "priority": "LOW",
                  "message": "hello",
                  "recipients": [{"recipientId": "erin@example.com"}],
                  "requestedChannels": ["EMAIL"]
                }
                """.formatted(key);

        String first = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String firstId = objectMapper.readTree(first).get("notificationId").asString();

        String second = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode secondJson = objectMapper.readTree(second);

        assertThat(secondJson.get("duplicate").asBoolean()).isTrue();
        assertThat(secondJson.get("notificationId").asString()).isEqualTo(firstId);
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

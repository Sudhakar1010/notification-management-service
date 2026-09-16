package com.nms.delivery.providers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises WebhookChannelProvider against WebhookSinkController over a real
 * HTTP call on the embedded server's actual random port -- proves the
 * status-code -> FailureType classification against real responses, not
 * simulated markers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "notification.delivery.poll-interval-ms=100",
        "notification.delivery.retry-base-delay-ms=100",
        "notification.delivery.retry-max-delay-ms=300",
        "notification.delivery.webhook-read-timeout-ms=300"
})
@AutoConfigureMockMvc
class WebhookChannelIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void webhookDeliverySucceedsAgainstARealHttpCall() throws Exception {
        JsonNode statusJson = submitAndAwaitTerminal(sinkUrl("ok"));

        assertThat(statusJson.get("overallStatus").asString()).isEqualTo("DELIVERED");
        JsonNode channel = statusJson.get("recipients").get(0).get("channels").get(0);
        assertThat(channel.get("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void notFoundResponseIsClassifiedAsInvalidRecipientAndIsNotRetried() throws Exception {
        JsonNode statusJson = submitAndAwaitTerminal(sinkUrl("not-found"));

        JsonNode channel = statusJson.get("recipients").get(0).get("channels").get(0);
        assertThat(channel.get("status").asString()).isEqualTo("FAILED");
        assertThat(channel.get("lastFailureType").asString()).isEqualTo("INVALID_RECIPIENT");
        assertThat(channel.get("attemptCount").asInt()).isEqualTo(1);
    }

    @Test
    void rateLimitResponseRetriesThenExhausts() throws Exception {
        JsonNode statusJson = submitAndAwaitTerminal(sinkUrl("rate-limit"));

        JsonNode channel = statusJson.get("recipients").get(0).get("channels").get(0);
        assertThat(channel.get("status").asString()).isEqualTo("EXHAUSTED");
        assertThat(channel.get("lastFailureType").asString()).isEqualTo("RATE_LIMITED");
        assertThat(channel.get("attemptCount").asInt()).isEqualTo(3);
    }

    @Test
    void slowEndpointIsClassifiedAsTimeout() throws Exception {
        JsonNode statusJson = submitAndAwaitTerminal(sinkUrl("timeout"));

        JsonNode channel = statusJson.get("recipients").get(0).get("channels").get(0);
        assertThat(channel.get("lastFailureType").asString()).isEqualTo("TIMEOUT");
    }

    private String sinkUrl(String scenario) {
        return "http://localhost:" + port + "/internal/webhook-sink/" + scenario;
    }

    private JsonNode submitAndAwaitTerminal(String webhookUrl) throws Exception {
        String body = """
                {
                  "idempotencyKey": "webhook-it-%s",
                  "sourceSystem": "integration-test",
                  "eventId": "corr-webhook-1",
                  "notificationType": "TEST",
                  "severity": "INFO",
                  "priority": "LOW",
                  "message": "hello",
                  "recipients": [{"recipientId": "%s"}],
                  "requestedChannels": ["WEBHOOK"]
                }
                """.formatted(Instant.now().toEpochMilli() + "-" + webhookUrl.hashCode(), webhookUrl);

        String submitResponse = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String notificationId = objectMapper.readTree(submitResponse).get("notificationId").asString();

        return pollUntilTerminal(notificationId);
    }

    private JsonNode pollUntilTerminal(String notificationId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(8));
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

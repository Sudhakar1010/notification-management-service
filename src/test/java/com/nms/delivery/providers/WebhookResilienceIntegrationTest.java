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
import java.util.UUID;

import static com.nms.testsupport.NotificationApiTestHelper.pollUntilTerminal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves ADR-016 (Resilience4j retry + circuit breaker around the webhook
 * call). Uses its own explicit resilience properties (distinct from other
 * webhook test classes) so Spring boots a dedicated context/port -- the
 * CircuitBreakerRegistry is a singleton bean, and its state must not leak
 * across test classes that happen to reuse a cached context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "notification.delivery.poll-interval-ms=100",
        "notification.delivery.retry-base-delay-ms=100",
        "notification.delivery.retry-max-delay-ms=300",
        "notification.delivery.webhook-read-timeout-ms=300",
        "notification.delivery.webhook.circuit-breaker.minimum-number-of-calls=5",
        "notification.delivery.webhook.retry.max-attempts=2"
})
@AutoConfigureMockMvc
class WebhookResilienceIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void flakyEndpointRecoversWithinOneOuterAttemptViaFastRetry() throws Exception {
        String flakyKey = UUID.randomUUID().toString();
        JsonNode statusJson = submitAndAwaitTerminal(sinkUrl("flaky/" + flakyKey), "flaky-" + flakyKey);

        JsonNode channel = statusJson.get("recipients").get(0).get("channels").get(0);
        assertThat(channel.get("status").asString()).isEqualTo("SUCCEEDED");
        // The fast inner retry absorbed the first (failing) call, so the
        // outer, DB-backed retry loop never needed a second attempt.
        assertThat(channel.get("attemptCount").asInt()).isEqualTo(1);
    }

    @Test
    void repeatedServerErrorsOpenTheCircuitForThatTarget() throws Exception {
        // Enough real 500s (each outer attempt fast-retries once more
        // internally) to cross minimumNumberOfCalls=5 and open the circuit
        // for this target authority.
        submitAndAwaitTerminal(sinkUrl("server-error"), "circuit-trip-" + Instant.now().toEpochMilli());

        // A second, different notification to a *different* path on the
        // same host:port -- would normally succeed, but the circuit is now
        // open for the whole target authority.
        JsonNode statusJson = submitAndAwaitTerminal(sinkUrl("ok"), "circuit-blocked-" + Instant.now().toEpochMilli());

        JsonNode channel = statusJson.get("recipients").get(0).get("channels").get(0);
        assertThat(channel.get("lastFailureReason").asString()).contains("Circuit breaker open");
    }

    private String sinkUrl(String scenario) {
        return "http://localhost:" + port + "/internal/webhook-sink/" + scenario;
    }

    private JsonNode submitAndAwaitTerminal(String webhookUrl, String idempotencyKey) throws Exception {
        String body = """
                {
                  "idempotencyKey": "%s",
                  "sourceSystem": "integration-test",
                  "eventId": "corr-resilience",
                  "notificationType": "TEST",
                  "severity": "INFO",
                  "priority": "LOW",
                  "message": "hello",
                  "recipients": [{"recipientId": "%s"}],
                  "requestedChannels": ["WEBHOOK"]
                }
                """.formatted(idempotencyKey, webhookUrl);

        String submitResponse = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String notificationId = objectMapper.readTree(submitResponse).get("notificationId").asString();

        return pollUntilTerminal(mockMvc, objectMapper, notificationId, Duration.ofSeconds(8));
    }
}

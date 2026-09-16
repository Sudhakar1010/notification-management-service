package com.nms.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers 4 gaps found by deliberately trying to break the running app
 * rather than just reading the code: a duplicated recipientId used to
 * crash with an unhandled 500, a reused idempotency key with a different
 * payload was silently accepted, and unexpected exceptions didn't share
 * the API's error response shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationRequestSafetyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void duplicateRecipientInOneRequestIsRejectedNotCrashed() throws Exception {
        String body = """
                {"idempotencyKey":"dup-recipient-%s","sourceSystem":"integration-test",
                 "eventId":"e1","notificationType":"TEST","severity":"INFO","priority":"LOW",
                 "message":"hi","recipients":[{"recipientId":"same@example.com"},{"recipientId":"same@example.com"}],
                 "requestedChannels":["EMAIL"]}
                """.formatted(Instant.now().toEpochMilli());

        String response = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("error").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(json.get("message").asString()).contains("duplicate recipientId");
    }

    @Test
    void idempotencyKeyReuseWithDifferentPayloadIsRejectedAsConflict() throws Exception {
        String key = "idem-conflict-" + Instant.now().toEpochMilli();
        String first = """
                {"idempotencyKey":"%s","sourceSystem":"integration-test","eventId":"e1",
                 "notificationType":"TEST","severity":"INFO","priority":"LOW","message":"original",
                 "recipients":[{"recipientId":"alice-safety@example.com"}],"requestedChannels":["EMAIL"]}
                """.formatted(key);
        mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isAccepted());

        String second = """
                {"idempotencyKey":"%s","sourceSystem":"integration-test","eventId":"e1",
                 "notificationType":"TEST","severity":"INFO","priority":"LOW","message":"completely different",
                 "recipients":[{"recipientId":"someone-else@example.com"}],"requestedChannels":["SMS"]}
                """.formatted(key);

        String response = mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("error").asString()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    void idempotencyKeyReuseWithIdenticalPayloadStillReplaysSuccessfully() throws Exception {
        String key = "idem-same-" + Instant.now().toEpochMilli();
        String body = """
                {"idempotencyKey":"%s","sourceSystem":"integration-test","eventId":"e1",
                 "notificationType":"TEST","severity":"INFO","priority":"LOW","message":"same every time",
                 "recipients":[{"recipientId":"bob-safety@example.com"}],"requestedChannels":["EMAIL"]}
                """.formatted(key);

        String first = mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String firstId = objectMapper.readTree(first).get("notificationId").asString();

        String second = mockMvc.perform(post("/api/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode secondJson = objectMapper.readTree(second);

        assertThat(secondJson.get("duplicate").asBoolean()).isTrue();
        assertThat(secondJson.get("notificationId").asString()).isEqualTo(firstId);
    }

    @Test
    void malformedJsonBodyReturnsConsistentErrorShape() throws Exception {
        String response = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content("{not valid json"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("error").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(json.has("message")).isTrue();
        assertThat(json.has("timestamp")).isTrue();
    }

    @Test
    void malformedNotificationIdPathVariableReturnsBadRequestNotServerError() throws Exception {
        String response = mockMvc.perform(get("/api/v1/notifications/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("error").asString()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void invalidEnumValueReturnsConsistentErrorShape() throws Exception {
        String body = """
                {"idempotencyKey":"bad-enum-%s","sourceSystem":"integration-test","eventId":"e1",
                 "notificationType":"TEST","severity":"NOT_A_REAL_SEVERITY","priority":"LOW","message":"hi",
                 "recipients":[{"recipientId":"a@example.com"}],"requestedChannels":["EMAIL"]}
                """.formatted(Instant.now().toEpochMilli());

        String response = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("error").asString()).isEqualTo("MALFORMED_REQUEST");
    }
}

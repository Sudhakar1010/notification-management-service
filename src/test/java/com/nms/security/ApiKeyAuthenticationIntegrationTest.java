package com.nms.security;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves ADR-018 with security actually turned on -- every other test class
 * runs with notification.security.enabled=false (the default) so the
 * capability's rollout doesn't require migrating every existing caller in
 * one step.
 */
@SpringBootTest(properties = {
        "notification.security.enabled=true",
        "notification.security.api-keys.test-key-1=trading-alerts"
})
@AutoConfigureMockMvc
class ApiKeyAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String body(String sourceSystem, String key) {
        return """
                {
                  "idempotencyKey": "auth-it-%s",
                  "sourceSystem": "%s",
                  "eventId": "corr-auth",
                  "notificationType": "TEST",
                  "severity": "INFO",
                  "priority": "LOW",
                  "message": "hello",
                  "recipients": [{"recipientId": "auth-test@example.com"}],
                  "requestedChannels": ["EMAIL"]
                }
                """.formatted(key + "-" + Instant.now().toEpochMilli(), sourceSystem);
    }

    @Test
    void missingApiKeyIsRejected() throws Exception {
        String response = mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("trading-alerts", "none")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("error").asString()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void invalidApiKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/notifications")
                        .header("X-Api-Key", "not-a-real-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("trading-alerts", "invalid")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validApiKeyWithMatchingSourceSystemSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/notifications")
                        .header("X-Api-Key", "test-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("trading-alerts", "match")))
                .andExpect(status().isAccepted());
    }

    @Test
    void validApiKeyWithMismatchedSourceSystemIsForbidden() throws Exception {
        String response = mockMvc.perform(post("/api/v1/notifications")
                        .header("X-Api-Key", "test-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("some-other-system", "mismatch")))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("error").asString()).isEqualTo("SOURCE_SYSTEM_MISMATCH");
    }

    @Test
    void statusAndAuditEndpointsAlsoRequireApiKey() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/notifications/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isUnauthorized());
    }
}

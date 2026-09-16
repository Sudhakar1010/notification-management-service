package com.nms.testsupport;

import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared polling helper for integration tests that submit a notification
 * and need to wait for the async worker to finish processing it. Was
 * previously copy-pasted (identically, apart from the deadline) across 6
 * test classes.
 */
public final class NotificationApiTestHelper {

    private NotificationApiTestHelper() {
    }

    public static JsonNode pollUntilTerminal(MockMvc mockMvc, ObjectMapper objectMapper,
                                              String notificationId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
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

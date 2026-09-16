package com.nms.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Config-gated, default-off so it doesn't break any existing caller/test
 * while the capability is rolled out (see ARCHITECTURE.md ADR-018).
 * `apiKeys` maps a key value to the sourceSystem it authenticates as.
 */
@Component
@ConfigurationProperties(prefix = "notification.security")
public class NotificationSecurityProperties {

    private boolean enabled = false;
    private Map<String, String> apiKeys = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(Map<String, String> apiKeys) {
        this.apiKeys = apiKeys;
    }
}

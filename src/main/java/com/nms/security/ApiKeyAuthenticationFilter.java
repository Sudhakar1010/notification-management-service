package com.nms.security;

import com.nms.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Authenticates callers of /api/v1/notifications/** via an X-Api-Key header,
 * config-gated behind notification.security.enabled (default false) so
 * existing/not-yet-migrated callers and tests aren't broken by rollout.
 * See ARCHITECTURE.md ADR-018. The resolved sourceSystem is stashed as a
 * request attribute for NotificationController to check against the
 * request body's sourceSystem field.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_SOURCE_SYSTEM_ATTR = "notification.authenticatedSourceSystem";
    private static final String API_KEY_HEADER = "X-Api-Key";

    private final NotificationSecurityProperties properties;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(NotificationSecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || !request.getRequestURI().startsWith("/api/v1/notifications");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        String sourceSystem = apiKey == null ? null : properties.getApiKeys().get(apiKey);

        if (sourceSystem == null) {
            respondUnauthorized(response);
            return;
        }

        request.setAttribute(AUTHENTICATED_SOURCE_SYSTEM_ATTR, sourceSystem);
        chain.doFilter(request, response);
    }

    private void respondUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        ErrorResponse body = new ErrorResponse("UNAUTHORIZED", "Missing or invalid X-Api-Key header");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

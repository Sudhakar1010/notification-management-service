package com.nms.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The X-Api-Key scheme is documented unconditionally even though
 * notification.security.enabled defaults to false (ADR-018) -- the contract
 * should describe how auth works once a deployment turns it on, not just
 * today's default.
 */
@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "ApiKeyAuth";

    @Bean
    public OpenAPI notificationManagementOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Notification Management Service")
                        .description("Accepts notification requests, routes them to recipient "
                                + "channels, delivers them asynchronously, and exposes status and "
                                + "audit history. See ARCHITECTURE.md in the repository for design "
                                + "rationale.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")
                                .description("Required only when notification.security.enabled=true "
                                        + "(off by default in this prototype -- see ADR-018).")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}

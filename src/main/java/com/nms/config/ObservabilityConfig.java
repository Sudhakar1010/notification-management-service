package com.nms.config;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exports finished spans to the application log via LoggingSpanExporter --
 * zero external infrastructure required, consistent with the rest of this
 * prototype (see ARCHITECTURE.md ADR-017). Spring Boot's tracing
 * autoconfiguration picks up any SpanExporter bean automatically. A real
 * deployment swaps this one bean for an OTLP exporter pointed at a
 * collector; nothing else about the tracing setup changes.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public LoggingSpanExporter loggingSpanExporter() {
        return LoggingSpanExporter.create();
    }
}

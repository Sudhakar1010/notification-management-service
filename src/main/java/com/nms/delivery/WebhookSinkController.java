package com.nms.delivery;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NOT part of the public API. A local, deterministic stand-in for a real
 * webhook receiver, so WebhookChannelProvider can be exercised against a
 * real HTTP call (real status codes, real timeouts) without depending on
 * an external service that could be flaky, offline, or absent in a
 * grading/CI sandbox. In production this endpoint would not exist --
 * WebhookChannelProvider works unmodified against any real http(s) URL,
 * since `recipientId` is that URL (see ARCHITECTURE.md ADR-012).
 */
@RestController
public class WebhookSinkController {

    private final Map<String, Integer> flakyCallCounts = new ConcurrentHashMap<>();

    @PostMapping("/internal/webhook-sink/{scenario}")
    public ResponseEntity<Void> receive(@PathVariable String scenario, @RequestBody(required = false) Object body)
            throws InterruptedException {
        return switch (scenario) {
            case "ok" -> ResponseEntity.ok().build();
            case "rate-limit" -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
            case "server-error" -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case "bad-request" -> ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            case "unauthorized" -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            case "not-found" -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case "timeout" -> {
                Thread.sleep(1000);
                yield ResponseEntity.ok().build();
            }
            default -> ResponseEntity.badRequest().build();
        };
    }

    /**
     * Fails with 500 on the first call for a given `key`, succeeds on every
     * call after that -- used to prove Resilience4j's fast retry (ADR-016)
     * recovers within one DeliveryAttemptProcessor attempt, distinct from
     * the slower, durable retry loop across attempts (ADR-011).
     */
    @PostMapping("/internal/webhook-sink/flaky/{key}")
    public ResponseEntity<Void> receiveFlaky(@PathVariable String key, @RequestBody(required = false) Object body) {
        int callNumber = flakyCallCounts.merge(key, 1, Integer::sum);
        if (callNumber == 1) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.ok().build();
    }
}

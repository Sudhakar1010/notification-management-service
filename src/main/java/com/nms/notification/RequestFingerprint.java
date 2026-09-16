package com.nms.notification;

import com.nms.notification.dto.NotificationRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;

/**
 * Deterministic fingerprint of a submission's semantic content, excluding
 * (sourceSystem, idempotencyKey) themselves -- those are the lookup key,
 * not part of what's being fingerprinted. Two submissions with the same
 * idempotency key are only a legitimate replay if this also matches; see
 * NotificationService and ARCHITECTURE.md ADR-014.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(NotificationRequest request) {
        String canonical = String.join("|",
                request.eventId(),
                request.notificationType(),
                String.valueOf(request.severity()),
                String.valueOf(request.priority()),
                nullToEmpty(request.subject()),
                request.message(),
                request.recipients().stream().map(r -> r.recipientId()).sorted()
                        .collect(Collectors.joining(",")),
                request.requestedChannels().stream().map(String::valueOf).sorted()
                        .collect(Collectors.joining(",")),
                String.valueOf(request.scheduledAt()),
                String.valueOf(request.expiresAt()));

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

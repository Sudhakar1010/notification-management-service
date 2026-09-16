package com.nms.exception;

/**
 * Thrown when a submission reuses an existing (sourceSystem, idempotencyKey)
 * pair but its content doesn't match the original request that key was
 * first used for -- see NotificationService's fingerprint check. Distinct
 * from a plain duplicate replay, which returns the original result because
 * the payload genuinely matches.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String message) {
        super(message);
    }
}

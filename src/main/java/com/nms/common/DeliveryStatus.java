package com.nms.common;

/**
 * Per-recipient, per-channel delivery attempt lifecycle.
 *
 * QUEUED -> SENDING -> SUCCEEDED
 *                    -> RETRY_SCHEDULED -> SENDING -> ... -> EXHAUSTED
 *                    -> FAILED (non-retryable failure classification)
 */
public enum DeliveryStatus {
    QUEUED,
    SENDING,
    SUCCEEDED,
    RETRY_SCHEDULED,
    FAILED,
    EXHAUSTED,
    SKIPPED
}

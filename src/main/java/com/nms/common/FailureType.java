package com.nms.common;

public enum FailureType {
    NONE,
    TRANSIENT_PROVIDER_ERROR,
    PERMANENT_PROVIDER_REJECTION,
    INVALID_RECIPIENT,
    RATE_LIMITED,
    TIMEOUT,
    AUTH_ERROR;

    /**
     * Whether a failure of this type is worth retrying. Permanent rejections
     * and invalid recipients will never succeed on retry; auth errors need a
     * human/config fix, not a retry.
     */
    public boolean isRetryable() {
        return this == TRANSIENT_PROVIDER_ERROR || this == RATE_LIMITED || this == TIMEOUT;
    }
}

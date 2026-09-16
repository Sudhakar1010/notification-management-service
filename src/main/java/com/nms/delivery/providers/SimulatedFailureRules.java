package com.nms.delivery.providers;

import com.nms.common.FailureType;
import com.nms.delivery.ProviderResult;

/**
 * Shared deterministic outcome rules for the simulated Email/SMS providers.
 * Extracted from EmailChannelProvider/SmsChannelProvider (see ARCHITECTURE.md
 * ADR-009) -- both providers previously duplicated this exact if/else chain.
 * See README "Mock provider simulation rules" for the marker list.
 */
public final class SimulatedFailureRules {

    private SimulatedFailureRules() {
    }

    public static ProviderResult evaluate(String recipientId, String channelLabel) {
        String recipient = recipientId.toLowerCase();

        if (recipient.contains("invalid")) {
            return ProviderResult.failure(FailureType.INVALID_RECIPIENT, channelLabel + " recipient is not valid");
        }
        if (recipient.contains("ratelimit")) {
            return ProviderResult.failure(FailureType.RATE_LIMITED, channelLabel + " provider rate limit exceeded");
        }
        if (recipient.contains("timeout")) {
            return ProviderResult.failure(FailureType.TIMEOUT, channelLabel + " provider timed out");
        }
        if (recipient.contains("authfail")) {
            return ProviderResult.failure(FailureType.AUTH_ERROR, channelLabel + " provider rejected credentials");
        }
        if (recipient.contains("failtransient")) {
            return ProviderResult.failure(FailureType.TRANSIENT_PROVIDER_ERROR, channelLabel + " provider temporarily unavailable");
        }
        if (recipient.contains("failpermanent")) {
            return ProviderResult.failure(FailureType.PERMANENT_PROVIDER_REJECTION, channelLabel + " provider permanently rejected message");
        }
        return ProviderResult.success(channelLabel + " accepted by provider");
    }
}

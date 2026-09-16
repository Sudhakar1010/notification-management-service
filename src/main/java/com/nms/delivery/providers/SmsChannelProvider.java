package com.nms.delivery.providers;

import com.nms.common.Channel;
import com.nms.common.FailureType;
import com.nms.delivery.ChannelProvider;
import com.nms.delivery.DeliveryContext;
import com.nms.delivery.ProviderResult;
import org.springframework.stereotype.Component;

/**
 * Simulated SMS provider -- no real Twilio/SNS integration in this prototype.
 * Deliberately duplicates EmailChannelProvider's simulation rules for now;
 * this duplication is the brownfield target extracted into a shared
 * FailureSimulator in Phase 2 (see ADR-009 in ARCHITECTURE.md).
 */
@Component
public class SmsChannelProvider implements ChannelProvider {

    @Override
    public Channel supportedChannel() {
        return Channel.SMS;
    }

    @Override
    public ProviderResult send(DeliveryContext context) {
        String recipient = context.recipientId().toLowerCase();

        if (recipient.contains("invalid")) {
            return ProviderResult.failure(FailureType.INVALID_RECIPIENT, "Phone number is not valid");
        }
        if (recipient.contains("ratelimit")) {
            return ProviderResult.failure(FailureType.RATE_LIMITED, "SMS provider rate limit exceeded");
        }
        if (recipient.contains("timeout")) {
            return ProviderResult.failure(FailureType.TIMEOUT, "SMS provider timed out");
        }
        if (recipient.contains("authfail")) {
            return ProviderResult.failure(FailureType.AUTH_ERROR, "SMS provider rejected credentials");
        }
        if (recipient.contains("failtransient")) {
            return ProviderResult.failure(FailureType.TRANSIENT_PROVIDER_ERROR, "SMS provider temporarily unavailable");
        }
        if (recipient.contains("failpermanent")) {
            return ProviderResult.failure(FailureType.PERMANENT_PROVIDER_REJECTION, "SMS provider permanently rejected message");
        }
        return ProviderResult.success("SMS accepted by provider");
    }
}

package com.nms.delivery.providers;

import com.nms.common.Channel;
import com.nms.common.FailureType;
import com.nms.delivery.ChannelProvider;
import com.nms.delivery.DeliveryContext;
import com.nms.delivery.ProviderResult;
import org.springframework.stereotype.Component;

/**
 * Simulated email provider -- no real SMTP/SES integration in this prototype.
 * Outcome is deterministic based on markers in the recipientId, so demos and
 * tests can reliably exercise every failure path. See README "Mock Provider
 * Simulation Rules" for the full list of markers.
 */
@Component
public class EmailChannelProvider implements ChannelProvider {

    @Override
    public Channel supportedChannel() {
        return Channel.EMAIL;
    }

    @Override
    public ProviderResult send(DeliveryContext context) {
        String recipient = context.recipientId().toLowerCase();

        if (recipient.contains("invalid")) {
            return ProviderResult.failure(FailureType.INVALID_RECIPIENT, "Email address is not valid");
        }
        if (recipient.contains("ratelimit")) {
            return ProviderResult.failure(FailureType.RATE_LIMITED, "Email provider rate limit exceeded");
        }
        if (recipient.contains("timeout")) {
            return ProviderResult.failure(FailureType.TIMEOUT, "Email provider timed out");
        }
        if (recipient.contains("authfail")) {
            return ProviderResult.failure(FailureType.AUTH_ERROR, "Email provider rejected credentials");
        }
        if (recipient.contains("failtransient")) {
            return ProviderResult.failure(FailureType.TRANSIENT_PROVIDER_ERROR, "Email provider temporarily unavailable");
        }
        if (recipient.contains("failpermanent")) {
            return ProviderResult.failure(FailureType.PERMANENT_PROVIDER_REJECTION, "Email provider permanently rejected message");
        }
        return ProviderResult.success("Email accepted by provider");
    }
}

package com.nms.delivery.providers;

import com.nms.common.Channel;
import com.nms.delivery.ChannelProvider;
import com.nms.delivery.DeliveryContext;
import com.nms.delivery.ProviderResult;
import org.springframework.stereotype.Component;

/**
 * Simulated SMS provider -- no real Twilio/SNS integration in this prototype.
 * Outcome is deterministic based on markers in the recipientId (see
 * SimulatedFailureRules / README "Mock Provider Simulation Rules").
 */
@Component
public class SmsChannelProvider implements ChannelProvider {

    @Override
    public Channel supportedChannel() {
        return Channel.SMS;
    }

    @Override
    public ProviderResult send(DeliveryContext context) {
        return SimulatedFailureRules.evaluate(context.recipientId(), "SMS");
    }
}

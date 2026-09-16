package com.nms.delivery;

import com.nms.common.Channel;

public interface ChannelProvider {

    Channel supportedChannel();

    ProviderResult send(DeliveryContext context);
}

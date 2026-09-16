package com.nms.notification.dto;

import java.util.List;

public record RecipientDeliveryView(
        String recipientId,
        List<ChannelDeliveryView> channels
) {
}

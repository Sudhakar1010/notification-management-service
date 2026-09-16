package com.nms.routing;

import com.nms.common.Channel;

import java.util.List;

public record RoutingDecision(List<Channel> selectedChannels, String reason) {

    public boolean isRoutable() {
        return !selectedChannels.isEmpty();
    }
}

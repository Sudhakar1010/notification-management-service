package com.nms.delivery;

import com.nms.common.Channel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChannelProviderRegistry {

    private final Map<Channel, ChannelProvider> providersByChannel;

    public ChannelProviderRegistry(List<ChannelProvider> providers) {
        this.providersByChannel = providers.stream()
                .collect(Collectors.toMap(ChannelProvider::supportedChannel, Function.identity()));
    }

    public Optional<ChannelProvider> find(Channel channel) {
        return Optional.ofNullable(providersByChannel.get(channel));
    }
}

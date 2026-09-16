package com.nms.routing;

import com.nms.common.Channel;
import com.nms.common.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutingServiceTest {

    @Mock
    private RecipientPreferenceRepository preferenceRepository;

    @Test
    void selectsAllRequestedChannelsWhenNoPreferencesExist() {
        when(preferenceRepository.findByRecipientId("alice")).thenReturn(List.of());
        RoutingService service = new RoutingService(preferenceRepository);

        RoutingDecision decision = service.decide("alice", List.of(Channel.EMAIL, Channel.SMS), Severity.INFO);

        assertThat(decision.selectedChannels()).containsExactly(Channel.EMAIL, Channel.SMS);
        assertThat(decision.isRoutable()).isTrue();
    }

    @Test
    void excludesChannelsTheRecipientHasOptedOutOf() {
        RecipientPreference optOut = new RecipientPreference();
        optOut.setRecipientId("bob");
        optOut.setChannel(Channel.SMS);
        optOut.setOptedIn(false);
        when(preferenceRepository.findByRecipientId("bob")).thenReturn(List.of(optOut));
        RoutingService service = new RoutingService(preferenceRepository);

        RoutingDecision decision = service.decide("bob", List.of(Channel.EMAIL, Channel.SMS), Severity.WARNING);

        assertThat(decision.selectedChannels()).containsExactly(Channel.EMAIL);
    }

    @Test
    void isUnroutableWhenEveryRequestedChannelIsOptedOut() {
        RecipientPreference optOut = new RecipientPreference();
        optOut.setRecipientId("carol");
        optOut.setChannel(Channel.EMAIL);
        optOut.setOptedIn(false);
        when(preferenceRepository.findByRecipientId("carol")).thenReturn(List.of(optOut));
        RoutingService service = new RoutingService(preferenceRepository);

        RoutingDecision decision = service.decide("carol", List.of(Channel.EMAIL), Severity.WARNING);

        assertThat(decision.isRoutable()).isFalse();
        assertThat(decision.selectedChannels()).isEmpty();
    }

    @Test
    void criticalSeverityOverridesRecipientOptOut() {
        RecipientPreference optOut = new RecipientPreference();
        optOut.setRecipientId("dana");
        optOut.setChannel(Channel.SMS);
        optOut.setOptedIn(false);
        when(preferenceRepository.findByRecipientId("dana")).thenReturn(List.of(optOut));
        RoutingService service = new RoutingService(preferenceRepository);

        RoutingDecision decision = service.decide("dana", List.of(Channel.EMAIL, Channel.SMS), Severity.CRITICAL);

        assertThat(decision.selectedChannels()).containsExactly(Channel.EMAIL, Channel.SMS);
        assertThat(decision.reason()).contains("severity=CRITICAL overrides opt-out");
    }

    @Test
    void criticalSeverityWithNoOptOutsBehavesLikeNormalRouting() {
        when(preferenceRepository.findByRecipientId("erin")).thenReturn(List.of());
        RoutingService service = new RoutingService(preferenceRepository);

        RoutingDecision decision = service.decide("erin", List.of(Channel.EMAIL), Severity.CRITICAL);

        assertThat(decision.selectedChannels()).containsExactly(Channel.EMAIL);
        assertThat(decision.reason()).doesNotContain("overrides opt-out");
    }
}

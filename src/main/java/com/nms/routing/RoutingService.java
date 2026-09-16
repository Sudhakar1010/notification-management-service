package com.nms.routing;

import com.nms.common.Channel;
import com.nms.common.Severity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Routing policy (ADR-010): requested channels minus the recipient's
 * explicit opt-outs, EXCEPT a CRITICAL notification overrides opt-outs
 * entirely and is routed to every requested channel. This is the
 * requirement's "routing policy" input made explicit, rather than left
 * unaddressed or built out as a separate configurable subsystem.
 *
 * `priority` is deliberately not part of this rule -- requirement 4.3
 * names severity as a routing input, not priority, so the override isn't
 * extended beyond what was actually asked for.
 *
 * Known gap (documented, not fixed here): `severity` is caller-supplied
 * with no authentication on who is submitting it (see ARCHITECTURE.md
 * "Production Readiness Backlog"). This rule assumes a trusted caller;
 * a real deployment must authenticate the source system before "CRITICAL
 * bypasses recipient consent" is safe to rely on.
 */
@Service
public class RoutingService {

    private final RecipientPreferenceRepository preferenceRepository;

    public RoutingService(RecipientPreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    public RoutingDecision decide(String recipientId, List<Channel> requestedChannels, Severity severity) {
        Set<Channel> optedOut = preferenceRepository.findByRecipientId(recipientId).stream()
                .filter(pref -> !pref.isOptedIn())
                .map(RecipientPreference::getChannel)
                .collect(Collectors.toSet());

        boolean overridden = severity == Severity.CRITICAL && !optedOut.isEmpty();

        List<Channel> selected = requestedChannels.stream()
                .filter(channel -> overridden || !optedOut.contains(channel))
                .distinct()
                .toList();

        String reason;
        if (overridden) {
            reason = "requested=%s, recipient opted out of=%s, severity=CRITICAL overrides opt-out, selected=%s"
                    .formatted(requestedChannels, optedOut, selected);
        } else if (optedOut.isEmpty()) {
            reason = "requested=%s, no opt-outs, selected=%s".formatted(requestedChannels, selected);
        } else {
            reason = "requested=%s, recipient opted out of=%s, selected=%s".formatted(requestedChannels, optedOut, selected);
        }

        return new RoutingDecision(selected, reason);
    }
}

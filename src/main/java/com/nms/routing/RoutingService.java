package com.nms.routing;

import com.nms.common.Channel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase-1 (greenfield) routing: requested channels minus the recipient's
 * explicit opt-outs. Severity/priority are not yet factored into channel
 * selection here -- the requirement lists requested channel, severity,
 * recipient preference and routing policy as routing inputs but does not
 * define how conflicts between them are resolved (e.g. can a CRITICAL
 * notification override an opt-out?). That precedence question is the
 * ambiguous requirement resolved in ADR-010 (see ARCHITECTURE.md); this
 * class is intentionally upgraded there rather than guessed at here.
 */
@Service
public class RoutingService {

    private final RecipientPreferenceRepository preferenceRepository;

    public RoutingService(RecipientPreferenceRepository preferenceRepository) {
        this.preferenceRepository = preferenceRepository;
    }

    public RoutingDecision decide(String recipientId, List<Channel> requestedChannels) {
        Set<Channel> optedOut = preferenceRepository.findByRecipientId(recipientId).stream()
                .filter(pref -> !pref.isOptedIn())
                .map(RecipientPreference::getChannel)
                .collect(Collectors.toSet());

        List<Channel> selected = requestedChannels.stream()
                .filter(channel -> !optedOut.contains(channel))
                .distinct()
                .toList();

        String reason = optedOut.isEmpty()
                ? "requested=%s, no opt-outs, selected=%s".formatted(requestedChannels, selected)
                : "requested=%s, recipient opted out of=%s, selected=%s".formatted(requestedChannels, optedOut, selected);

        return new RoutingDecision(selected, reason);
    }
}

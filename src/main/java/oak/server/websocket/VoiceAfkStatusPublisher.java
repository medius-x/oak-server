package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import oak.server.controller.AfkController;
import oak.server.dto.VoiceAfkStatusEvent;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.entity.ServerProfile;
import oak.server.repository.ServerProfileRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Publishes the AFK dimension of the voice-activity registry as presence-style deltas on
 * {@code /topic/voice.afk} (user request 2026-11-04): a voice member whose last heartbeat
 * is older than the configured AFK timeout goes yellow in the roster, a fresh heartbeat
 * returns them to green. This reuses the SAME registry and timeout the room-sweep
 * ({@link VoiceAfkSweeper}) decides by — but runs INDEPENDENTLY of the AFK channel: the
 * roster indicator works even when no AFK room is configured. Only TRANSITIONS are
 * published (one frame per flip); a member who left voice drops out of the set (their
 * next join re-arms the timeout from the join-time touch). Clients seed the initial set
 * from {@code GET /api/voice/afk}; this bean is its source of truth.
 *
 * <p>{@code @Transactional} sits on the scheduled method itself (the RefreshTokenCleanup
 * self-invocation rule — the scheduler enters through the CGLIB proxy).
 */
@Component
@RequiredArgsConstructor
public class VoiceAfkStatusPublisher {

    public static final String TOPIC = "/topic/voice.afk";

    private final VoiceMembershipTracker tracker;
    private final VoiceActivityRegistry registry;
    private final ServerProfileRepository profileRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /** The currently-away set — the pull side's source of truth, transitions included. */
    private final Set<String> away = new HashSet<>();

    /** The presence-dot cadence — heartbeats arrive every 30 s, the room sweep runs at 30 s. */
    @Scheduled(fixedDelayString = "${oak.voice.afk-status-interval:PT30S}")
    @Transactional
    public void publishAfkTransitions() {
        ServerProfile profile = profileRepository.singleton();
        Instant deadline = Instant.now().minus(Duration.ofMinutes(AfkController.timeoutOf(profile)));
        Set<String> current = new HashSet<>();
        for (VoiceChannelMembersDto room : tracker.snapshot()) {
            for (String username : room.usernames()) {
                if (registry.lastActive(username).isBefore(deadline)) {
                    current.add(username);
                }
            }
        }
        for (String username : current) {
            if (away.add(username)) {
                messagingTemplate.convertAndSend(TOPIC, new VoiceAfkStatusEvent(username, true));
            }
        }
        for (String username : away) {
            if (!current.contains(username)) {
                messagingTemplate.convertAndSend(TOPIC, new VoiceAfkStatusEvent(username, false));
            }
        }
        away.retainAll(current);
    }

    /** A defensive copy for the snapshot endpoint (the set only mutates on the scheduler thread). */
    public Set<String> currentAfk() {
        return Set.copyOf(away);
    }
}

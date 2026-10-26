package oak.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.controller.AfkController;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.dto.VoiceControlEvent;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Permission;
import oak.server.entity.ServerProfile;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ServerProfileRepository;
import oak.server.service.AuthorizationService;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The AFK sweep: every tick, every voice member whose last reported activity is older
 * than the configured timeout is moved into the configured AFK channel ({@code
 * VoiceControlEvent("MOVED", afkChannelId, null)} makes the honest client re-run
 * {@code /room} and reconnect there; the gateway kick tears the old media). Members
 * WITHOUT the AFK room's CONNECT are force-left instead of moved. Disabled entirely
 * while no AFK channel is configured.
 *
 * <p>{@code @Transactional} sits on the scheduled method itself — the scheduler enters
 * through the CGLIB proxy, and a private transactional helper would bypass it (the
 * RefreshTokenCleanup lesson). The pure decision logic is kept in-package testable
 * through {@link #isIdle(VoiceActivityRegistry, String, Instant)}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceAfkSweeper {

    private final ServerProfileRepository profileRepository;
    private final ChannelRepository channelRepository;
    private final VoiceMembershipTracker tracker;
    private final VoiceActivityRegistry registry;
    private final AuthorizationService auth;
    private final oak.server.service.JanusVoiceAccessService voiceAccess;
    private final VoiceAccessEnforcer enforcer;
    private final SimpMessagingTemplate messagingTemplate;
    private final AfkVoicePolicy afkVoicePolicy;

    /** The sweep granularity — timeouts below 5 minutes are configured, not swept, finer. */
    @Scheduled(fixedDelayString = "${oak.voice.afk-sweep-interval:PT30S}")
    @Transactional
    public void sweep() {
        ServerProfile profile = profileRepository.singleton();
        if (profile.getAfkChannelId() == null) {
            return; // the feature is off
        }
        Optional<Channel> target = channelRepository.findById(profile.getAfkChannelId())
                .filter(channel -> channel.getType() == ChannelType.VOICE);
        if (target.isEmpty()) {
            return; // a dangling/stale setting — deleting the channel resets it, this is belt-and-braces
        }
        int timeoutMinutes = AfkController.timeoutOf(profile);
        Instant deadline = Instant.now().minus(Duration.ofMinutes(timeoutMinutes));
        for (VoiceChannelMembersDto room : tracker.snapshot()) {
            if (room.channelId().equals(target.get().getId())) {
                continue; // already in the AFK room
            }
            for (String username : room.usernames()) {
                if (isIdle(registry, username, deadline)) {
                    moveToAfk(room.channelId(), target.get(), username, timeoutMinutes);
                }
            }
        }
    }

    /** The sweep's decision, package-visible for the unit tests. */
    static boolean isIdle(VoiceActivityRegistry registry, String username, Instant deadline) {
        return registry.lastActive(username).isBefore(deadline);
    }

    private void moveToAfk(Long fromChannelId, Channel afkChannel, String username, int timeoutMinutes) {
        // The WHY of the ejection, spelled out for the log (live reports 2026-10: present
        // members swept with no visible cause — "idle for Nm" next to the configured
        // timeout and the last heartbeat timestamp names the guilty side immediately:
        // a healthy client heartbeats every 30 s, so a lastActive minutes old means the
        // heartbeats stopped arriving, not that the sweep fired early).
        Duration idleFor = Duration.between(registry.lastActive(username), Instant.now());
        String reason = "idle %dm %ds of the %dm timeout; last active %s"
                .formatted(idleFor.toMinutes(), idleFor.toSecondsPart(), timeoutMinutes, registry.lastActive(username));
        Optional<User> member = auth.findUser(username);
        if (member.isEmpty() || !auth.hasInChannel(member.get(), afkChannel, Permission.CONNECT)) {
            // Cannot sit in the AFK room → out of voice entirely (the enforcer's stance).
            log.info("[afk] leaving {} from channel {} ({})", username, fromChannelId, reason);
            enforcer.forceLeave(fromChannelId, username, "afk");
            return;
        }
        tracker.move(fromChannelId, afkChannel.getId(), username);
        // Tear the old gateway publishers — the MOVED push makes the honest client re-join
        // (a fresh /room mints a fresh token for the new room; no revocation needed).
        voiceAccess.forceKick(fromChannelId, username);
        messagingTemplate.convertAndSendToUser(username, VoiceAccessEnforcer.VOICE_CONTROL_QUEUE,
                new VoiceControlEvent("MOVED", afkChannel.getId(), null));
        // The AFK suppression goes out AFTER the MOVED push on purpose (one socket, TCP
        // order): the client's re-join resets ITS OWN marks, and the mod-mute state push
        // arriving AFTER that reset is what survives it.
        afkVoicePolicy.applyOnEnter(username, afkChannel.getId());
        log.info("[afk] moved {} from channel {} to {} ({})", username, fromChannelId, afkChannel.getId(), reason);
    }
}

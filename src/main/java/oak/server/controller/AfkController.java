package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.dto.AfkSettingsRequest;
import oak.server.dto.AfkSettingsResponse;
import oak.server.dto.VoiceChannelMembersDto;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Permission;
import oak.server.entity.ServerProfile;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ServerProfileRepository;
import oak.server.service.AuthorizationService;
import oak.server.service.JanusVoiceAccessService;
import oak.server.websocket.VoiceMembershipTracker;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The AFK-sweep settings (the InviteController idiom — full paths per method; the TTL
 * precedent of a feature-scoped setting living next to its feature rather than under
 * /api/server's MANAGE_SERVER gate). Gated by MANAGE_CHANNELS: the AFK channel IS a
 * voice channel, so whoever may manage rooms may steer the sweep.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class AfkController {

    static final int DEFAULT_AFK_TIMEOUT_MINUTES = 30;
    /** The viewer-facing channel list carries the AFK flag — changes ping this topic. */
    private static final String CHANNELS_CHANGED_TOPIC = "/topic/channels.changed";

    private final ServerProfileRepository profileRepository;
    private final ChannelRepository channelRepository;
    private final AuthorizationService auth;
    private final SimpMessagingTemplate messagingTemplate;
    private final VoiceMembershipTracker tracker;
    private final JanusVoiceAccessService voiceAccess;
    private final oak.server.websocket.AfkVoicePolicy afkVoicePolicy;

    @GetMapping("/api/server/afk")
    public AfkSettingsResponse settings() {
        ServerProfile profile = profileRepository.singleton();
        return new AfkSettingsResponse(profile.getAfkChannelId(), timeoutOf(profile));
    }

    @PutMapping("/api/server/afk")
    @Transactional
    public AfkSettingsResponse update(@RequestBody @Valid AfkSettingsRequest request,
                                      @AuthenticationPrincipal UserDetails principal) {
        auth.requireServer(auth.requireUser(principal.getUsername()), Permission.MANAGE_CHANNELS);
        if (request.afkChannelId() != null) {
            Channel channel = channelRepository.findById(request.afkChannelId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown channel"));
            if (channel.getType() != ChannelType.VOICE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AFK channel must be a voice channel");
            }
        }
        ServerProfile profile = profileRepository.singleton();
        Long previousAfkChannelId = profile.getAfkChannelId();
        profile.setAfkChannelId(request.afkChannelId());
        profile.setAfkTimeoutMinutes(request.afkTimeoutMinutes());
        profileRepository.save(profile);
        // The AFK flag rides ChannelResponse — every viewer refetches the channel list to
        // re-badge the rooms. AFTER COMMIT (the invites lesson: an in-tx ping lets
        // subscribers refetch before the new setting is visible).
        pingChannelsChangedAfterCommit();
        // The designation leaving a channel lifts that channel's suppression: the AFK
        // policy's moderator mutes live in the tracker and would otherwise be re-seeded
        // onto every re-entry forever (a self-unmute can never lift them). Lifted even
        // for members not currently sitting there — the mark outlives their leave.
        if (previousAfkChannelId != null && !previousAfkChannelId.equals(request.afkChannelId())) {
            tracker.clearModeratorMutes(previousAfkChannelId);
            for (VoiceChannelMembersDto room : tracker.snapshot()) {
                if (room.channelId().equals(previousAfkChannelId)) {
                    for (String username : room.usernames()) {
                        voiceAccess.moderateAudio(previousAfkChannelId, username, false);
                    }
                }
            }
            log.info("[afk] designation left channel {} — moderator mutes lifted", previousAfkChannelId);
        }
        if (request.afkChannelId() != null && !request.afkChannelId().equals(previousAfkChannelId)) {
            // The designation LANDING on a room that already has people in it suppresses
            // them retroactively (Discord: the AFK room mutes everyone in it, whenever
            // they entered — the enter hook alone would leave the sitting members talking).
            for (VoiceChannelMembersDto room : tracker.snapshot()) {
                if (room.channelId().equals(request.afkChannelId())) {
                    for (String username : room.usernames()) {
                        afkVoicePolicy.applyOnEnter(username, request.afkChannelId());
                    }
                }
            }
        }
        return new AfkSettingsResponse(profile.getAfkChannelId(), timeoutOf(profile));
    }

    private void pingChannelsChangedAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            messagingTemplate.convertAndSend(CHANNELS_CHANGED_TOPIC, System.currentTimeMillis());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messagingTemplate.convertAndSend(CHANNELS_CHANGED_TOPIC, System.currentTimeMillis());
            }
        });
    }

    /** Null on rows predating the column resolves to the code default (the invite-TTL pattern). */
    public static int timeoutOf(ServerProfile profile) {
        return profile.getAfkTimeoutMinutes() != null ? profile.getAfkTimeoutMinutes() : DEFAULT_AFK_TIMEOUT_MINUTES;
    }
}

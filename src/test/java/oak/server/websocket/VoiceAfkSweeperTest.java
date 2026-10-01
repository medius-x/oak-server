package oak.server.websocket;

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
import oak.server.service.JanusVoiceAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of the AFK sweep: an idle member is moved (registry move → gateway kick
 * → the MOVED push, in that order), an active member stays, the feature is off without a
 * configured channel, a non-VOICE/stale target skips, and a member without the AFK
 * room's CONNECT is force-left instead.
 */
@ExtendWith(MockitoExtension.class)
class VoiceAfkSweeperTest {

    @Mock
    private ServerProfileRepository profileRepository;
    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private VoiceMembershipTracker tracker;
    @Mock
    private VoiceActivityRegistry registry;
    @Mock
    private AuthorizationService auth;
    @Mock
    private JanusVoiceAccessService voiceAccess;
    @Mock
    private VoiceAccessEnforcer enforcer;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private AfkVoicePolicy afkVoicePolicy;

    @InjectMocks
    private VoiceAfkSweeper sweeper;

    @Test
    void movesAnIdleMemberInOrder() {
        ServerProfile profile = profileWithAfk(77L, 30);
        Channel afk = channel(77L);
        User member = new User("alice", "hash");
        member.setId(1L);
        when(profileRepository.singleton()).thenReturn(profile);
        when(channelRepository.findById(77L)).thenReturn(Optional.of(afk));
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(7L, List.of("alice"))));
        when(registry.lastActive("alice")).thenReturn(Instant.now().minusSeconds(3600));
        when(auth.findUser("alice")).thenReturn(Optional.of(member));
        when(auth.hasInChannel(member, afk, Permission.CONNECT)).thenReturn(true);

        sweeper.sweep();

        InOrder order = inOrder(tracker, voiceAccess, messagingTemplate, afkVoicePolicy);
        order.verify(tracker).move(7L, 77L, "alice");
        order.verify(voiceAccess).forceKick(7L, "alice");
        order.verify(messagingTemplate).convertAndSendToUser(eq("alice"),
                eq(VoiceAccessEnforcer.VOICE_CONTROL_QUEUE),
                eq(new VoiceControlEvent("MOVED", 77L, null)));
        // The suppression push goes out AFTER MOVED — the client's re-join resets its
        // own marks first, the mod-mute state arriving after that reset survives it.
        order.verify(afkVoicePolicy).applyOnEnter("alice", 77L);
        verify(enforcer, never()).forceLeave(any(), any(), any());
    }

    @Test
    void anActiveMemberStaysPut() {
        when(profileRepository.singleton()).thenReturn(profileWithAfk(77L, 30));
        when(channelRepository.findById(77L)).thenReturn(Optional.of(channel(77L)));
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(7L, List.of("alice"))));
        when(registry.lastActive("alice")).thenReturn(Instant.now()); // touched just now

        sweeper.sweep();

        verify(tracker, never()).move(any(), any(), any());
        verifyNoInteractions(voiceAccess, messagingTemplate);
    }

    @Test
    void theMemberAlreadyInTheAfkRoomIsSkipped() {
        when(profileRepository.singleton()).thenReturn(profileWithAfk(77L, 30));
        when(channelRepository.findById(77L)).thenReturn(Optional.of(channel(77L)));
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(77L, List.of("alice"))));
        // No activity-registry stub: the room filter short-circuits before the idle check.

        sweeper.sweep();

        verify(tracker, never()).move(any(), any(), any());
        verifyNoInteractions(registry, voiceAccess, messagingTemplate);
    }

    @Test
    void withoutAConfiguredChannelTheSweepDoesNothing() {
        when(profileRepository.singleton()).thenReturn(new ServerProfile("srv")); // afkChannelId null

        sweeper.sweep();

        verifyNoInteractions(tracker, channelRepository, voiceAccess, messagingTemplate);
    }

    @Test
    void aNonVoiceOrVanishedTargetSkipsTheSweep() {
        when(profileRepository.singleton()).thenReturn(profileWithAfk(77L, 30));
        when(channelRepository.findById(77L)).thenReturn(Optional.of(channelOfType(77L, ChannelType.TEXT)));

        sweeper.sweep();

        verifyNoInteractions(tracker, voiceAccess, messagingTemplate);
    }

    @Test
    void aMemberWithoutConnectOnTheAfkRoomIsForceLeft() {
        ServerProfile profile = profileWithAfk(77L, 30);
        Channel afk = channel(77L);
        User member = new User("alice", "hash");
        member.setId(1L);
        when(profileRepository.singleton()).thenReturn(profile);
        when(channelRepository.findById(77L)).thenReturn(Optional.of(afk));
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(7L, List.of("alice"))));
        when(registry.lastActive("alice")).thenReturn(Instant.EPOCH);
        when(auth.findUser("alice")).thenReturn(Optional.of(member));
        when(auth.hasInChannel(member, afk, Permission.CONNECT)).thenReturn(false);

        sweeper.sweep();

        verify(enforcer).forceLeave(7L, "alice", "afk");
        verify(tracker, never()).move(any(), any(), any());
    }

    private static ServerProfile profileWithAfk(Long afkChannelId, int timeoutMinutes) {
        ServerProfile profile = new ServerProfile("srv");
        profile.setAfkChannelId(afkChannelId);
        profile.setAfkTimeoutMinutes(timeoutMinutes);
        return profile;
    }

    private static Channel channel(Long id) {
        return channelOfType(id, ChannelType.VOICE);
    }

    private static Channel channelOfType(Long id, ChannelType type) {
        Channel channel = new Channel("room", type);
        channel.setId(id);
        return channel;
    }
}

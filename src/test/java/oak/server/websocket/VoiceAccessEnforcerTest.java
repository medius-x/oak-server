package oak.server.websocket;

import oak.server.dto.VoiceChannelMembersDto;
import oak.server.dto.VoiceControlEvent;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.service.AuthorizationService;
import oak.server.service.JanusVoiceAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of the live-session enforcement: which members {@code enforceConnect} evicts (and
 * the exact four-step recipe it runs), that an empty room costs no database roundtrip, that
 * {@code kickAll} (channel deletion) rechecks nobody, and the recipe's ordering. Plain Mockito —
 * the tracker/auth/gateway are mocked, so no broker and no Janus are involved.
 */
@ExtendWith(MockitoExtension.class)
class VoiceAccessEnforcerTest {

    @Mock
    private VoiceMembershipTracker tracker;
    @Mock
    private AuthorizationService auth;
    @Mock
    private JanusVoiceAccessService voiceAccess;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private ChannelRepository channelRepository;

    @InjectMocks
    private VoiceAccessEnforcer enforcer;

    @Test
    void enforceConnectKicksTheLoserAndSparesTheKeeper() {
        Channel lounge = voiceChannel(7L);
        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        when(tracker.snapshot())
                .thenReturn(List.of(new VoiceChannelMembersDto(7L, List.of("alice", "bob"))));
        when(channelRepository.findById(7L)).thenReturn(Optional.of(lounge));
        when(auth.findUser("alice")).thenReturn(Optional.of(alice));
        when(auth.findUser("bob")).thenReturn(Optional.of(bob));
        when(auth.hasInChannel(alice, lounge, Permission.CONNECT)).thenReturn(false);
        when(auth.hasInChannel(bob, lounge, Permission.CONNECT)).thenReturn(true);

        enforcer.enforceConnect(7L, "admin");

        verify(tracker).kick(7L, "alice");
        verify(voiceAccess).forceKick(7L, "alice");
        verify(voiceAccess).revokeTokens(7L, "alice");
        verify(messagingTemplate).convertAndSendToUser(eq("alice"),
                eq(VoiceAccessEnforcer.VOICE_CONTROL_QUEUE),
                eq(new VoiceControlEvent("KICKED", 7L, "admin")));
        verify(tracker, never()).kick(7L, "bob");
        verify(voiceAccess, never()).forceKick(7L, "bob");
    }

    @Test
    void enforceConnectOnAnEmptyRoomSkipsTheDatabaseEntirely() {
        // The overwhelmingly common mutation happens on a channel with nobody in voice — it must
        // cost nothing beyond the registry snapshot.
        when(tracker.snapshot()).thenReturn(List.of());

        enforcer.enforceConnect(7L, "admin");

        verify(channelRepository, never()).findById(any());
        verifyNoInteractions(auth, voiceAccess, messagingTemplate);
    }

    @Test
    void enforceConnectForAnUnknownChannelIsANoOp() {
        // Defensive branch: unreachable from the override hooks (they validated the channel), so
        // an occupied room + a vanished row must simply do nothing rather than NPE.
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(7L, List.of("alice"))));
        when(channelRepository.findById(7L)).thenReturn(Optional.empty());

        enforcer.enforceConnect(7L, "admin");

        verify(tracker, never()).kick(anyLong(), anyString());
        verifyNoInteractions(auth, voiceAccess, messagingTemplate);
    }

    @Test
    void enforceConnectEvictsADeletedUserSittingInVoice() {
        // A user row that no longer resolves has no permissions by definition — kick, and never
        // even ask hasInChannel (there is no User to ask about).
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(7L, List.of("ghost"))));
        when(channelRepository.findById(7L)).thenReturn(Optional.of(voiceChannel(7L)));
        when(auth.findUser("ghost")).thenReturn(Optional.empty());

        enforcer.enforceConnect(7L, "admin");

        verify(tracker).kick(7L, "ghost");
        verify(voiceAccess).forceKick(7L, "ghost");
        verify(voiceAccess).revokeTokens(7L, "ghost");
    }

    @Test
    void kickAllEvictsEveryoneWithoutRecheckingPermissions() {
        // Channel deletion: the channel is going away, so CONNECT resolution is moot — nobody is
        // looked up, nobody is spared, and the database is never touched.
        when(tracker.snapshot())
                .thenReturn(List.of(new VoiceChannelMembersDto(7L, List.of("alice", "bob"))));

        enforcer.kickAll(7L, "admin");

        verify(tracker).kick(7L, "alice");
        verify(tracker).kick(7L, "bob");
        verify(voiceAccess).forceKick(7L, "alice");
        verify(voiceAccess).forceKick(7L, "bob");
        verify(voiceAccess).revokeTokens(7L, "alice");
        verify(voiceAccess).revokeTokens(7L, "bob");
        verifyNoInteractions(auth);
        verify(channelRepository, never()).findById(any());
    }

    @Test
    void kickAllOnAnEmptyRoomIsANoOp() {
        when(tracker.snapshot()).thenReturn(List.of());

        enforcer.kickAll(7L, "admin");

        verifyNoInteractions(voiceAccess, messagingTemplate);
    }

    @Test
    void forceLeaveRunsTheFourStepRecipeInOrder() {
        // Registry truth first (LEFT for everyone), then the forced layers, then the cooperative
        // push — a client that sees KICKED before its registry exit would flicker in the UI.
        enforcer.forceLeave(7L, "bob", "alice");

        InOrder order = inOrder(tracker, voiceAccess, messagingTemplate);
        order.verify(tracker).kick(7L, "bob");
        order.verify(voiceAccess).forceKick(7L, "bob");
        order.verify(voiceAccess).revokeTokens(7L, "bob");
        order.verify(messagingTemplate).convertAndSendToUser(eq("bob"),
                eq(VoiceAccessEnforcer.VOICE_CONTROL_QUEUE), any(VoiceControlEvent.class));
    }

    // --- fixtures ---

    private static Channel voiceChannel(Long id) {
        Channel channel = new Channel("lounge", ChannelType.VOICE);
        channel.setId(id);
        return channel;
    }

    private static User user(Long id, String username) {
        User user = new User(username, "hash");
        user.setId(id);
        return user;
    }
}

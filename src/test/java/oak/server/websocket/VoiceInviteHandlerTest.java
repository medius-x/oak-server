package oak.server.websocket;

import oak.server.dto.VoiceInvite;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.service.AuthorizationService;
import oak.server.service.DirectContactGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link VoiceInviteHandler}: the relay stamps the sender, resolves
 * the channel name itself, and stays SILENT on every malformed or forbidden shape
 * (a non-VOICE channel, a sender without VIEW_CHANNEL, blank fields, a gated
 * recipient) — the call.signal discipline of not leaking anything through an error.
 */
@ExtendWith(MockitoExtension.class)
class VoiceInviteHandlerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private AuthorizationService auth;
    @Mock
    private DirectContactGate contactGate;

    @InjectMocks
    private VoiceInviteHandler handler;

    @Test
    void relaysWithStampedSenderAndResolvedChannelName() {
        Channel room = channel(7L, "lobby", ChannelType.VOICE);
        when(channelRepository.findById(7L)).thenReturn(Optional.of(room));
        User sender = new User("alice", "pw");
        when(auth.findUser("alice")).thenReturn(Optional.of(sender));
        when(auth.hasInChannel(sender, room, Permission.VIEW_CHANNEL)).thenReturn(true);
        when(contactGate.canReach(eq("alice"), eq("bob"), eq(true))).thenReturn(true);

        handler.relay(new VoiceInvite(7L, null, null, "bob"), principal("alice"));

        ArgumentCaptor<VoiceInvite> captor = ArgumentCaptor.forClass(VoiceInvite.class);
        verify(messagingTemplate).convertAndSendToUser(anyString(), anyString(), captor.capture());
        VoiceInvite delivered = captor.getValue();
        assertEquals(7L, delivered.channelId());
        assertEquals("lobby", delivered.channelName());
        assertEquals("alice", delivered.from());
        assertEquals("bob", delivered.to());
    }

    @Test
    void staysSilentWhenTheRecipientIsGated() {
        // M1: blocks, DM restriction and rate limits apply to invite cards too.
        Channel room = channel(7L, "lobby", ChannelType.VOICE);
        when(channelRepository.findById(7L)).thenReturn(Optional.of(room));
        User sender = new User("alice", "pw");
        when(auth.findUser("alice")).thenReturn(Optional.of(sender));
        when(auth.hasInChannel(sender, room, Permission.VIEW_CHANNEL)).thenReturn(true);
        when(contactGate.canReach(eq("alice"), eq("bob"), eq(true))).thenReturn(false);

        handler.relay(new VoiceInvite(7L, null, null, "bob"), principal("alice"));

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void staysSilentOnNonVoiceChannelsAndBlankTargets() {
        when(channelRepository.findById(9L)).thenReturn(Optional.of(channel(9L, "general", ChannelType.TEXT)));
        handler.relay(new VoiceInvite(9L, null, null, "bob"), principal("alice"));

        handler.relay(new VoiceInvite(null, null, null, "bob"), principal("alice"));
        handler.relay(new VoiceInvite(7L, null, null, "  "), principal("alice"));
        handler.relay(null, principal("alice"));

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void staysSilentWhenTheSenderCannotSeeTheRoom() {
        Channel room = channel(7L, "hidden", ChannelType.VOICE);
        when(channelRepository.findById(7L)).thenReturn(Optional.of(room));
        User sender = new User("mallory", "pw");
        when(auth.findUser("mallory")).thenReturn(Optional.of(sender));
        when(auth.hasInChannel(sender, room, Permission.VIEW_CHANNEL)).thenReturn(false);

        handler.relay(new VoiceInvite(7L, null, null, "bob"), principal("mallory"));

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void staysSilentWhenTheRoomDoesNotExist() {
        when(channelRepository.findById(404L)).thenReturn(Optional.empty());
        handler.relay(new VoiceInvite(404L, null, null, "bob"), principal("alice"));
        verifyNoInteractions(messagingTemplate);
    }

    private static Channel channel(Long id, String name, ChannelType type) {
        Channel channel = new Channel(name, type);
        channel.setId(id);
        return channel;
    }

    private static Principal principal(String name) {
        return () -> name;
    }
}

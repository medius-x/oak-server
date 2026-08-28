package oak.server.service;

import oak.server.dto.ChannelResponse;
import oak.server.dto.CreateChannelRequest;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.User;
import oak.server.repository.ChannelParticipantRepository;
import oak.server.repository.ChannelReadStateRepository;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.ChannelUserOverrideRepository;
import oak.server.repository.MessageRepository;
import oak.server.websocket.VoiceAccessEnforcer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito tests for the channel lifecycle wiring: creating a channel persists it and broadcasts it
 * on the STOMP channels topic, so every connected client adds it to its sidebar live (the same
 * REST-write → push pattern as {@code MessageService}); deleting one evicts its live voice room
 * BEFORE the data purge ({@code VoiceAccessEnforcer}) and then broadcasts the deleted id.
 */
@ExtendWith(MockitoExtension.class)
class ChannelServiceTest {

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private ChannelRoleOverrideRepository channelRoleOverrideRepository;
    @Mock
    private ChannelUserOverrideRepository channelUserOverrideRepository;
    @Mock
    private ChannelParticipantRepository channelParticipantRepository;
    @Mock
    private ChannelReadStateRepository channelReadStateRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private AuthorizationService auth;
    @Mock
    private VoiceAccessEnforcer voiceEnforcer;
    @Mock
    private JanusVoiceAccessService voiceAccess;

    @InjectMocks
    private ChannelService channelService;

    @Test
    void createPersistsChannelAndBroadcastsItToChannelsTopic() {
        Channel saved = new Channel("general", ChannelType.TEXT);
        saved.setId(42L);
        when(channelRepository.save(any(Channel.class))).thenReturn(saved);

        ChannelResponse response = channelService.create(new CreateChannelRequest("general", ChannelType.TEXT), "alice");

        assertEquals(42L, response.id());
        assertEquals("general", response.name());
        assertEquals(ChannelType.TEXT, response.type());
        verify(messagingTemplate).convertAndSend(eq(ChannelService.CHANNELS_TOPIC), any(ChannelResponse.class));
    }

    @Test
    void createDefaultsTypeToTextAndBroadcastsIt() {
        Channel saved = new Channel("lounge", ChannelType.TEXT);
        saved.setId(7L);
        when(channelRepository.save(any(Channel.class))).thenReturn(saved);

        channelService.create(new CreateChannelRequest("lounge", null), "alice");

        ArgumentCaptor<ChannelResponse> captor = ArgumentCaptor.forClass(ChannelResponse.class);
        verify(messagingTemplate).convertAndSend(eq(ChannelService.CHANNELS_TOPIC), captor.capture());
        assertEquals(ChannelType.TEXT, captor.getValue().type());
        assertEquals("lounge", captor.getValue().name());
    }

    @Test
    void deleteEvictsLiveVoiceSessionsBeforePurgingChildren() {
        // The eviction must precede the purge: the LEFT/KICKED pushes then reference a channel
        // that still exists for concurrent readers.
        Channel channel = new Channel("temp", ChannelType.VOICE);
        channel.setId(42L);
        when(channelRepository.findById(42L)).thenReturn(Optional.of(channel));
        when(auth.requireUser("alice")).thenReturn(user(1L, "alice"));

        channelService.delete(42L, "alice");

        // Registry eviction first, then the gateway room teardown (same executor, submission
        // order), then the data purge.
        InOrder order = inOrder(voiceEnforcer, voiceAccess, messageRepository);
        order.verify(voiceEnforcer).kickAll(42L, "alice");
        order.verify(voiceAccess).destroyRoom(42L);
        order.verify(messageRepository).deleteByChannelId(42L);
        verify(messagingTemplate).convertAndSend(eq(ChannelService.CHANNELS_DELETED_TOPIC), eq(42L));
    }

    @Test
    void deleteOfAMissingChannelKicksNobody() {
        // The 404 short-circuit gates the eviction — a no-op delete on a missing channel must
        // never evict a (stale) room or destroy anything.
        when(channelRepository.findById(42L)).thenReturn(Optional.empty());
        when(auth.requireUser("alice")).thenReturn(user(1L, "alice"));

        assertThrows(ResponseStatusException.class, () -> channelService.delete(42L, "alice"));

        verify(voiceEnforcer, never()).kickAll(any(), anyString());
        verify(voiceAccess, never()).destroyRoom(anyLong());
    }

    @Test
    void renameTrimsPingsChannelsChangedAndReturnsViewerLocked() {
        Channel channel = new Channel("general", ChannelType.VOICE);
        channel.setId(42L);
        User actor = user(1L, "alice");
        when(auth.requireUser("alice")).thenReturn(actor);
        when(channelRepository.findById(42L)).thenReturn(Optional.of(channel));
        when(channelRepository.save(any(Channel.class))).thenReturn(channel);
        when(auth.resolveChannelMask(actor, channel)).thenReturn(0L); // no CONNECT → locked

        ChannelResponse response = channelService.rename(42L, "  lounge  ", "alice");

        assertEquals("lounge", response.name());
        assertTrue(response.locked(), "the response carries the actor's own locked flag");
        verify(messagingTemplate).convertAndSend(
                eq(ChannelService.CHANNELS_CHANGED_TOPIC), any(Long.class));
    }

    @Test
    void renameOfAMissingChannelThrows404() {
        when(auth.requireUser("alice")).thenReturn(user(1L, "alice"));
        when(channelRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> channelService.rename(42L, "lounge", "alice"));
    }

    @Test
    void renameRejectsDmChannels() {
        // DM renames belong to the participants (PATCH /api/channels/{id}); the admin route must
        // refuse DM ids — otherwise an admin could rename someone's private conversation.
        Channel dm = new Channel("dm", ChannelType.DM);
        dm.setId(9L);
        when(auth.requireUser("alice")).thenReturn(user(1L, "alice"));
        when(channelRepository.findById(9L)).thenReturn(Optional.of(dm));

        assertThrows(ResponseStatusException.class, () -> channelService.rename(9L, "nope", "alice"));
    }

    // --- fixtures ---

    private static User user(Long id, String username) {
        User user = new User(username, "hash");
        user.setId(id);
        return user;
    }
}

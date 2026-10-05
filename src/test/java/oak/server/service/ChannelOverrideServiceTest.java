package oak.server.service;

import oak.server.dto.ChannelOverrideResponse;
import oak.server.dto.UpsertOverrideRequest;
import oak.server.entity.Channel;
import oak.server.entity.ChannelType;
import oak.server.entity.Permission;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.ChannelRepository;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.ChannelUserOverrideRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.websocket.VoiceAccessEnforcer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code /topic/channels.changed} ping: every successful override mutation (role or member,
 * upsert or delete) must broadcast it, so connected clients refetch their viewer-filtered channel
 * list and pick up visibility flips live. The ping's body carries no channel data — only that it
 * fired is asserted here.
 */
@ExtendWith(MockitoExtension.class)
class ChannelOverrideServiceTest {

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ChannelRoleOverrideRepository overrideRepository;
    @Mock
    private ChannelUserOverrideRepository userOverrideRepository;
    @Mock
    private AuthorizationService auth;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private VoiceAccessEnforcer voiceEnforcer;

    @InjectMocks
    private ChannelOverrideService channelOverrideService;

    @Test
    void roleUpsertPingsChannelsChanged() {
        Channel channel = channel(7L);
        Role moderators = role(3L);
        User owner = user(1L, "alice");
        when(auth.requireUser("alice")).thenReturn(owner);
        when(channelRepository.findById(7L)).thenReturn(Optional.of(channel));
        when(roleRepository.findById(3L)).thenReturn(Optional.of(moderators));
        when(overrideRepository.findByChannelIdAndRoleId(7L, 3L)).thenReturn(Optional.empty());
        oak.server.entity.ChannelRoleOverride saved =
                new oak.server.entity.ChannelRoleOverride(channel, moderators,
                        Permission.VIEW_CHANNEL.bit, 0L);
        when(overrideRepository.save(any())).thenReturn(saved);

        ChannelOverrideResponse response = channelOverrideService.upsert(7L, 3L,
                new UpsertOverrideRequest(java.util.Set.of("VIEW_CHANNEL"), java.util.Set.of()), "alice");

        assertEquals(3L, response.roleId());
        verify(messagingTemplate).convertAndSend(
                eq(ChannelService.CHANNELS_CHANGED_TOPIC), any(Long.class));
        verify(voiceEnforcer).enforceChannelChange(7L, "alice");
    }

    @Test
    void roleDeletePingsChannelsChanged() {
        when(auth.requireUser("alice")).thenReturn(user(1L, "alice"));
        when(overrideRepository.findByChannelIdAndRoleId(7L, 3L))
                .thenReturn(Optional.of(new oak.server.entity.ChannelRoleOverride(
                        channel(7L), role(3L), 0L, 0L)));

        channelOverrideService.delete(7L, 3L, "alice");

        verify(messagingTemplate).convertAndSend(
                eq(ChannelService.CHANNELS_CHANGED_TOPIC), any(Long.class));
        verify(voiceEnforcer).enforceChannelChange(7L, "alice");
    }

    @Test
    void userOverrideUpsertPingsChannelsChanged() {
        when(auth.requireUser("alice")).thenReturn(user(1L, "alice"));
        when(channelRepository.findById(7L)).thenReturn(Optional.of(channel(7L)));
        when(userRepository.findById(11L)).thenReturn(Optional.of(user(11L, "bob")));
        when(userOverrideRepository.findByChannelIdAndUserId(7L, 11L)).thenReturn(Optional.empty());
        when(userOverrideRepository.save(any())).thenReturn(new oak.server.entity.ChannelUserOverride(
                channel(7L), user(11L, "bob"), Permission.VIEW_CHANNEL.bit, 0L));

        channelOverrideService.upsertUserOverride(7L, 11L,
                new UpsertOverrideRequest(java.util.Set.of("VIEW_CHANNEL"), java.util.Set.of()), "alice");

        verify(messagingTemplate).convertAndSend(
                eq(ChannelService.CHANNELS_CHANGED_TOPIC), any(Long.class));
        verify(voiceEnforcer).enforceChannelChange(7L, "alice");
    }

    @Test
    void userOverrideDeletePingsChannelsChanged() {
        when(auth.requireUser("alice")).thenReturn(user(1L, "alice"));
        when(userOverrideRepository.findByChannelIdAndUserId(7L, 11L))
                .thenReturn(Optional.of(new oak.server.entity.ChannelUserOverride(
                        channel(7L), user(11L, "bob"), 0L, 0L)));

        channelOverrideService.deleteUserOverride(7L, 11L, "alice");

        verify(messagingTemplate).convertAndSend(
                eq(ChannelService.CHANNELS_CHANGED_TOPIC), any(Long.class));
        verify(voiceEnforcer).enforceChannelChange(7L, "alice");
    }

    @Test
    void nonManagerCannotUpsertAndNothingIsBroadcast() {
        User intruder = user(2L, "bob");
        when(auth.requireUser("bob")).thenReturn(intruder);
        // requireServer is void — stub it with doThrow (the service relies on its 403 exception).
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing permission"))
                .when(auth).requireServer(any(), any());

        assertThrows(ResponseStatusException.class, () -> channelOverrideService.upsert(7L, 3L,
                new UpsertOverrideRequest(java.util.Set.of(), java.util.Set.of()), "bob"));

        org.mockito.Mockito.verify(messagingTemplate, org.mockito.Mockito.never())
                .convertAndSend(anyString(), any(Object.class));
        // A denied mutation enforces nothing either — no eviction may fire for it.
        org.mockito.Mockito.verify(voiceEnforcer, org.mockito.Mockito.never())
                .enforceChannelChange(any(), anyString());
    }

    // --- fixtures ---

    private static Channel channel(Long id) {
        Channel channel = new Channel("general", ChannelType.TEXT);
        channel.setId(id);
        return channel;
    }

    private static Role role(Long id) {
        Role role = new Role("Mods", 1, 0L, false, false, RoleKind.CUSTOM);
        role.setId(id);
        return role;
    }

    private static User user(Long id, String username) {
        User user = new User(username, "hash");
        user.setId(id);
        return user;
    }
}

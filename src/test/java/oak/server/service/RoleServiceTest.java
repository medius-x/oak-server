package oak.server.service;

import oak.server.dto.UpdateRoleRequest;
import oak.server.entity.Permission;
import oak.server.entity.Role;
import oak.server.entity.RoleKind;
import oak.server.entity.User;
import oak.server.repository.ChannelRoleOverrideRepository;
import oak.server.repository.RoleRepository;
import oak.server.repository.UserRepository;
import oak.server.websocket.VoiceAccessEnforcer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of the live-voice enforcement hooks in {@link RoleService}: a permission-mask
 * change and a role deletion sweep every occupied room ({@code enforceEverywhere}), while a
 * cosmetic-only edit must not — the sweep costs a database look per occupied member.
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthorizationService auth;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private ChannelRoleOverrideRepository channelRoleOverrideRepository;
    @Mock
    private VoiceAccessEnforcer voiceEnforcer;

    @InjectMocks
    private RoleService roleService;

    @Test
    void permissionMaskChangeSweepsEverywhere() {
        Role moderators = role(3L, Permission.CONNECT.bit);
        when(auth.requireUser("alice")).thenReturn(user(1L, "alice"));
        when(roleRepository.findById(3L)).thenReturn(Optional.of(moderators));
        when(roleRepository.save(any(Role.class))).thenReturn(moderators);

        // The mask changes (CONNECT dropped) — every holder in every room must be rechecked.
        roleService.update(3L, new UpdateRoleRequest(null, Set.of("VIEW_CHANNEL"), null, null, null),
                "alice");

        verify(voiceEnforcer).enforceEverywhere("alice");
    }

    @Test
    void updateWithoutAPermissionChangeSweepsNothing() {
        Role moderators = role(3L, Permission.CONNECT.bit);
        when(auth.requireUser("alice")).thenReturn(user(1L, "alice"));
        when(roleRepository.findById(3L)).thenReturn(Optional.of(moderators));
        when(roleRepository.save(any(Role.class))).thenReturn(moderators);

        // A cosmetic-only edit (name) — the mask is untouched, so no sweep may fire.
        roleService.update(3L, new UpdateRoleRequest("Mods", null, null, null, null), "alice");

        verify(voiceEnforcer, never()).enforceEverywhere(anyString());
    }

    @Test
    void roleDeletionSweepsEverywhere() {
        Role moderators = role(3L, Permission.CONNECT.bit);
        when(auth.requireUser("alice")).thenReturn(user(1L, "alice"));
        when(roleRepository.findById(3L)).thenReturn(Optional.of(moderators));
        when(userRepository.findUsersByRoleId(3L)).thenReturn(List.of());
        when(channelRoleOverrideRepository.findByRole_Id(3L)).thenReturn(List.of());

        roleService.delete(3L, "alice");

        // Holders lost the role and its channel overrides died with it — everything rechecks.
        verify(voiceEnforcer).enforceEverywhere("alice");
    }

    // --- fixtures ---

    private static Role role(Long id, long permissions) {
        Role role = new Role("Mods", 1, permissions, false, false, RoleKind.CUSTOM);
        role.setId(id);
        return role;
    }

    private static User user(Long id, String username) {
        User user = new User(username, "hash");
        user.setId(id);
        return user;
    }
}

package oak.server.websocket;

import oak.server.dto.VoiceControlCommand;
import oak.server.entity.User;
import oak.server.service.AuthorizationService;
import oak.server.service.JanusVoiceAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of the self-toggle branch in {@link VoiceControlHandler}: mute/deafen on YOURSELF needs
 * no permission, no hierarchy check and no database lookup (the STOMP principal is already
 * authenticated), writes the tracker's SELF state set, and (mute) still goes through the forced
 * gateway layer. Other targets keep the permission gate; malformed commands are silent no-ops.
 */
@ExtendWith(MockitoExtension.class)
class VoiceControlHandlerTest {

    @Mock
    private VoiceMembershipTracker tracker;
    @Mock
    private AuthorizationService auth;
    @Mock
    private JanusVoiceAccessService voiceAccess;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private VoiceControlHandler handler;

    @Test
    void selfMuteNeedsNoPermissionAndStillForcesTheGateway() {
        handler.mute(command(7L, "alice", true, null), principal("alice"));

        verify(tracker).setSelfMuted(7L, "alice", true);
        verify(voiceAccess).moderateAudio(7L, "alice", true);
        verifyNoInteractions(auth); // no permission/hierarchy resolution for self
    }

    @Test
    void selfDeafenNeedsNoPermissionAndStaysCooperative() {
        handler.deafen(command(7L, "alice", null, true), principal("alice"));

        verify(tracker).setSelfDeafened(7L, "alice", true);
        verifyNoInteractions(auth, voiceAccess); // deafen has no forced counterpart (documented)
    }

    @Test
    void otherTargetWithoutPermissionDoesNothing() {
        when(auth.findUser("alice")).thenReturn(Optional.of(user(1L, "alice")));
        when(auth.findUser("bob")).thenReturn(Optional.of(user(2L, "bob")));
        when(auth.hasServer(any(), any())).thenReturn(false);

        handler.mute(command(7L, "bob", true, null), principal("alice"));

        verify(tracker, never()).setMuted(anyLong(), anyString(), anyBoolean());
        verifyNoInteractions(voiceAccess);
    }

    @Test
    void malformedCommandsAreSilentNoOps() {
        handler.mute(null, principal("alice"));
        handler.mute(command(null, "alice", true, null), principal("alice"));
        handler.deafen(command(7L, null, null, true), principal("alice"));

        verifyNoInteractions(tracker, voiceAccess, auth);
    }

    // --- fixtures ---

    private static VoiceControlCommand command(Long channelId, String target, Boolean muted, Boolean deafened) {
        return new VoiceControlCommand(channelId, target, muted, deafened);
    }

    private static Principal principal(String username) {
        return () -> username;
    }

    private static User user(Long id, String username) {
        User user = new User(username, "hash");
        user.setId(id);
        return user;
    }
}

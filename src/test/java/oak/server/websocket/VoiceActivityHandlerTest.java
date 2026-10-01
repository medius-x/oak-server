package oak.server.websocket;

import oak.server.dto.VoiceChannelMembersDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of the AFK-activity heartbeat: a sender occupying a voice room touches
 * the registry; a sender in no room (and a null principal) is silently ignored — the
 * sweep only consults members of rooms, so their heartbeats cannot matter.
 */
@ExtendWith(MockitoExtension.class)
class VoiceActivityHandlerTest {

    @Mock
    private VoiceMembershipTracker tracker;
    @Mock
    private VoiceActivityRegistry registry;

    @InjectMocks
    private VoiceActivityHandler handler;

    @Test
    void touchesTheRegistryForAMemberOfARoom() {
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(7L, List.of("alice", "bob"))));

        handler.activity(() -> "alice");

        verify(registry).touch("alice");
    }

    @Test
    void ignoresASenderInNoVoiceRoom() {
        when(tracker.snapshot()).thenReturn(List.of(new VoiceChannelMembersDto(7L, List.of("bob"))));

        handler.activity(() -> "carol");

        verifyNoInteractions(registry);
    }

    @Test
    void ignoresANullPrincipal() {
        handler.activity(null);

        verifyNoInteractions(tracker, registry);
    }

    @Test
    void anEmptySnapshotTouchesNothing() {
        when(tracker.snapshot()).thenReturn(List.of());

        handler.activity((Principal) () -> "alice");

        verify(registry, never()).touch("alice");
    }
}

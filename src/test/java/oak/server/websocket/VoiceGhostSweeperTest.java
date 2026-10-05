package oak.server.websocket;

import oak.server.dto.VoiceChannelMembersDto;
import oak.server.service.JanusVoiceAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The H9 ghost sweep: every OCCUPIED room (tracker ∪ token registry) is probed for
 * unknown-display publishers; idle rooms cost nothing and never reach the gateway.
 */
@ExtendWith(MockitoExtension.class)
class VoiceGhostSweeperTest {

    @Mock
    private VoiceMembershipTracker tracker;

    @Mock
    private JanusVoiceAccessService voiceAccess;

    @InjectMocks
    private VoiceGhostSweeper sweeper;

    @Test
    void sweepProbesEachOccupiedRoomWithTheUnionOfKnownMembers() {
        when(tracker.snapshot()).thenReturn(List.of(
                new VoiceChannelMembersDto(7L, List.of("alice", "bob"))));
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of(
                7L, List.of("carol"),          // token holder invisible to the STOMP tracker
                9L, List.of("dave")));         // occupied ONLY per the token registry

        sweeper.sweep();

        verify(voiceAccess).kickUnknownPublishers(eq(7L), eq(Set.of("alice", "bob", "carol")));
        verify(voiceAccess).kickUnknownPublishers(eq(9L), eq(Set.of("dave")));
    }

    @Test
    void sweepSkipsIdleRooms() {
        when(tracker.snapshot()).thenReturn(List.of());
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of());

        sweeper.sweep();

        verify(voiceAccess, never()).kickUnknownPublishers(anyLong(), anySet());
    }
}

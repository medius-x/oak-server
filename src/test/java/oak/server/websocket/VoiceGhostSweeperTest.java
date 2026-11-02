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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The H9 ghost sweep: every OCCUPIED room (tracker ∪ token registry) is probed for
 * unknown-display publishers; idle rooms cost nothing and never reach the gateway.
 * The vanish healing and the offline token TTL ride the same tick (live report 2026-10-30).
 */
@ExtendWith(MockitoExtension.class)
class VoiceGhostSweeperTest {

    @Mock
    private VoiceMembershipTracker tracker;

    @Mock
    private JanusVoiceAccessService voiceAccess;

    @Mock
    private VoiceAccessEnforcer evictor;

    @Mock
    private PresenceTracker presence;

    @InjectMocks
    private VoiceGhostSweeper sweeper;

    @Test
    void sweepProbesEachOccupiedRoomWithTheUnionOfKnownMembers() {
        when(tracker.snapshot()).thenReturn(List.of(
                new VoiceChannelMembersDto(7L, List.of("alice", "bob"))));
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of(
                7L, List.of("carol"),          // token holder invisible to the STOMP tracker
                9L, List.of("dave")));         // occupied ONLY per the token registry
        when(presence.onlineUsernames()).thenReturn(Set.of("alice", "bob", "carol", "dave"));

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

    @Test
    void vanishedRoomHealsTheRegistriesInsteadOfReJoiningForever() {
        // The room is gone from the gateway: every registry member is evicted dry (no
        // control join against a dead room — that was the whole-day WARN loop), and a
        // live client re-joins through /room on its KICKED push.
        when(tracker.snapshot()).thenReturn(List.of(
                new VoiceChannelMembersDto(6L, List.of("alice"))));
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of(
                6L, List.of("stale-holder")));
        when(voiceAccess.probeRoom(6L)).thenReturn(JanusVoiceAccessService.RoomProbe.MISSING);
        when(presence.onlineUsernames()).thenReturn(Set.of());

        sweeper.sweep();

        verify(evictor).evictFromVanishedRoom(6L, "alice", "ghost-sweep");
        verify(evictor).evictFromVanishedRoom(6L, "stale-holder", "ghost-sweep");
        verify(voiceAccess, never()).kickUnknownPublishers(anyLong(), anySet());
    }

    @Test
    void probeTransportFailureDegradesToThePlainSweep() {
        // A gateway hiccup must NEVER trigger registry healing (the never-a-false-verdict
        // stance): the tick falls back to the ordinary ghost join.
        when(tracker.snapshot()).thenReturn(List.of(
                new VoiceChannelMembersDto(7L, List.of("alice"))));
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of());
        when(voiceAccess.probeRoom(7L)).thenThrow(new RuntimeException("gateway unreachable"));
        when(presence.onlineUsernames()).thenReturn(Set.of("alice"));

        sweeper.sweep();

        verify(voiceAccess).kickUnknownPublishers(7L, Set.of("alice"));
        verify(evictor, never()).evictFromVanishedRoom(anyLong(), anyString(), anyString());
    }

    @Test
    void offlineTokenHoldersLoseTheirTokensAfterTheTtl() {
        // A holder absent from presence for OFFLINE_TOKEN_TICKS consecutive ticks loses every
        // token; a holder that comes back online drops out of the counter within a tick.
        when(tracker.snapshot()).thenReturn(List.of());
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of(
                5L, List.of("dead-client", "blip")));
        when(presence.onlineUsernames()).thenReturn(Set.of());

        for (int tick = 0; tick < 4; tick++) {
            sweeper.sweep();
        }
        verify(voiceAccess, never()).revokeTokensForUser(anyString());

        // The blipping holder returns online: its counter resets, the dead one hits the TTL.
        when(presence.onlineUsernames()).thenReturn(Set.of("blip"));
        sweeper.sweep();

        verify(voiceAccess).revokeTokensForUser("dead-client");
        verify(voiceAccess, never()).revokeTokensForUser("blip");
    }

    @Test
    void offlineCounterDoesNotFireForHoldersTheRegistryAlreadyDropped() {
        // The counter only accumulates while the registry still lists the holder — a revoked
        // token set must not resurrect the user into a later revoke.
        when(tracker.snapshot()).thenReturn(List.of());
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of(
                5L, List.of("dead-client")));
        when(presence.onlineUsernames()).thenReturn(Set.of());

        for (int tick = 0; tick < 3; tick++) {
            sweeper.sweep();
        }
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of());
        sweeper.sweep();
        sweeper.sweep();

        verify(voiceAccess, never()).revokeTokensForUser(anyString());
    }
}

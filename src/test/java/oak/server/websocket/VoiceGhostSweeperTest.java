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
    void aVisibleHolderWithoutACounterMustNotBlowUpTheTick() {
        // The live UOE regression (2026-11-18): a holder PRESENT in the channel's membership
        // and holding NO invisible counter is the ordinary state of every visible participant
        // every tick — the old getOrDefault(..., Map.of()).remove(...) threw
        // UnsupportedOperationException right there, killing the whole sweep pass.
        when(tracker.snapshot()).thenReturn(List.of(
                new VoiceChannelMembersDto(7L, List.of("alice"))));
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of(
                7L, List.of("alice", "carol")));
        when(presence.onlineUsernames()).thenReturn(Set.of("alice", "carol"));

        for (int tick = 0; tick < VoiceGhostSweeper.INVISIBLE_TOKEN_TICKS; tick++) {
            sweeper.sweep(); // must not throw; carol's counter keeps accumulating meanwhile
        }

        verify(voiceAccess).revokeTokens(7L, "carol");
        verify(voiceAccess, never()).revokeTokens(7L, "alice");
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

    @Test
    void invisibleHolderLosesTheChannelTokenAfterTheGrace() {
        // The silent listener: STOMP-online, token live, NO voice.join → invisible to the
        // membership for INVISIBLE_TOKEN_TICKS consecutive ticks → the CHANNEL's tokens are
        // revoked (the gateway tears the session down). The offline TTL never fires — the
        // holder is online.
        when(tracker.snapshot()).thenReturn(List.of());
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of(
                5L, List.of("silent-listener")));
        when(presence.onlineUsernames()).thenReturn(Set.of("silent-listener"));

        for (int tick = 0; tick < VoiceGhostSweeper.INVISIBLE_TOKEN_TICKS - 1; tick++) {
            sweeper.sweep();
        }
        verify(voiceAccess, never()).revokeTokens(anyLong(), anyString());

        sweeper.sweep();

        verify(voiceAccess).revokeTokens(5L, "silent-listener");
        verify(voiceAccess, never()).revokeTokensForUser(anyString());
    }

    @Test
    void joiningTheChannelResetsTheInvisibleCounter() {
        // An honest holder briefly absent (the /room → voice.join ramp, a STOMP reconnect
        // resync) must never hit the grace: membership presence resets the counter, and the
        // next invisible streak starts from zero.
        when(tracker.snapshot()).thenReturn(List.of());
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of(
                5L, List.of("honest")));
        when(presence.onlineUsernames()).thenReturn(Set.of("honest"));

        sweeper.sweep();
        sweeper.sweep(); // two invisible ticks — one short of the grace
        when(tracker.snapshot()).thenReturn(List.of(
                new VoiceChannelMembersDto(5L, List.of("honest"))));
        sweeper.sweep(); // joined: the counter resets
        when(tracker.snapshot()).thenReturn(List.of());
        sweeper.sweep();
        sweeper.sweep(); // two more invisible ticks — STILL one short

        verify(voiceAccess, never()).revokeTokens(anyLong(), anyString());
    }

    @Test
    void invisibleRevocationIsChannelScoped() {
        // A holder of tokens in TWO channels, present in only one: the absent channel's
        // tokens are revoked, the present one keeps theirs.
        when(tracker.snapshot()).thenReturn(List.of(
                new VoiceChannelMembersDto(8L, List.of("multi"))));
        when(voiceAccess.tokenHoldersByChannel()).thenReturn(Map.of(
                5L, List.of("multi"),
                8L, List.of("multi")));
        when(presence.onlineUsernames()).thenReturn(Set.of("multi"));

        for (int tick = 0; tick < VoiceGhostSweeper.INVISIBLE_TOKEN_TICKS; tick++) {
            sweeper.sweep();
        }

        verify(voiceAccess).revokeTokens(5L, "multi");
        verify(voiceAccess, never()).revokeTokens(eq(8L), anyString());
    }
}

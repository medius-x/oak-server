package oak.server.websocket;

import oak.server.dto.VoiceChannelMembersDto;
import oak.server.dto.VoiceControlState;
import oak.server.dto.VoiceMemberEvent;
import oak.server.dto.VoiceMemberStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Mockito unit test for the voice-membership registry. Mirrors the {@code PresenceTrackerTest} pattern
 * (mock {@link SimpMessagingTemplate}, verify pushes via {@link ArgumentCaptor}) and focuses on the
 * refcount/boundary logic and idempotency against duplicate disconnect events.
 */
@ExtendWith(MockitoExtension.class)
class VoiceMembershipTrackerTest {

    private static final String VOICE_TOPIC = VoiceMembershipTracker.VOICE_TOPIC;
    private static final String VOICE_STATE_TOPIC = VoiceMembershipTracker.VOICE_STATE_TOPIC;
    private static final Long CHANNEL_A = 10L;
    private static final Long CHANNEL_B = 20L;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private VoiceMembershipTracker tracker;

    @Test
    void joinThenLeavePublishesJoinedAndLeft() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.exit("s1");

        List<VoiceMemberEvent> events = captureEvents(2);
        assertEquals("alice", events.get(0).username());
        assertEquals(CHANNEL_A, events.get(0).channelId());
        assertEquals(VoiceMemberStatus.JOINED, events.get(0).status());
        assertEquals(VoiceMemberStatus.LEFT, events.get(1).status());
        assertTrue(tracker.snapshot().isEmpty());
    }

    @Test
    void multiSessionSameChannelPublishesOnFirstJoinAndLastLeave() {
        tracker.enter("s1", "alice", CHANNEL_A); // first session → JOINED
        tracker.enter("s2", "alice", CHANNEL_A); // second tab → no event
        tracker.exit("s1");                       // one tab closed → still a member
        tracker.exit("s2");                       // last tab → LEFT

        List<VoiceMemberEvent> events = captureEvents(2);
        assertEquals(VoiceMemberStatus.JOINED, events.get(0).status());
        assertEquals(VoiceMemberStatus.LEFT, events.get(1).status());
        assertTrue(tracker.snapshot().isEmpty());
    }

    @Test
    void duplicateJoinIsNoOp() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.enter("s1", "alice", CHANNEL_A); // same session + channel → no-op

        verify(messagingTemplate, times(1)).convertAndSend(eq(VOICE_TOPIC), any(VoiceMemberEvent.class));
        assertEquals(List.of("alice"), usernamesIn(CHANNEL_A));
    }

    @Test
    void moveRePointsEverySessionLeftOldJoinedNew() {
        tracker.enter("s1", "alice", CHANNEL_A); // JOINED A
        tracker.enter("s2", "alice", CHANNEL_A); // second session, no event
        tracker.enter("s3", "bob", CHANNEL_A);   // JOINED A (a bystander stays put)

        tracker.move(CHANNEL_A, CHANNEL_B, "alice"); // the AFK sweep

        // Both of alice's sessions re-pointed: bob keeps room A alone, alice lives in B.
        // Event order follows the refcounts: the FIRST re-point opens B (0→1 → JOINED B),
        // the SECOND closes A (1→0 → LEFT A) — one LEFT and one JOINED in total.
        List<VoiceMemberEvent> events = captureEvents(4);
        assertEquals(VoiceMemberStatus.JOINED, events.get(2).status());
        assertEquals(CHANNEL_B, events.get(2).channelId());
        assertEquals("alice", events.get(2).username());
        assertEquals(VoiceMemberStatus.LEFT, events.get(3).status());
        assertEquals(CHANNEL_A, events.get(3).channelId());
        assertEquals("alice", events.get(3).username());
        assertEquals(List.of("bob"), usernamesIn(CHANNEL_A));
        assertEquals(List.of("alice"), usernamesIn(CHANNEL_B));
    }

    @Test
    void moveOfAMemberInNoRoomIsANoOp() {
        tracker.enter("s1", "bob", CHANNEL_A);

        tracker.move(CHANNEL_A, CHANNEL_B, "carol");

        verify(messagingTemplate, times(1)).convertAndSend(eq(VOICE_TOPIC), any(VoiceMemberEvent.class));
        assertEquals(List.of("bob"), usernamesIn(CHANNEL_A));
    }

    @Test
    void clearModeratorMutesLiftsEveryModMuteInTheChannelIncludingAbsentMembers() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.setSelfMuted(CHANNEL_A, "alice", true);
        tracker.setMuted(CHANNEL_A, "alice", true);
        // bob LEFT the channel long ago — his surviving mark must still be lifted, or the
        // join-time re-seed would resurrect his mute forever (the AFK designation leaving).
        tracker.enter("s2", "bob", CHANNEL_A);
        tracker.setMuted(CHANNEL_A, "bob", true);
        tracker.exit("s2");

        tracker.clearModeratorMutes(CHANNEL_A);

        // alice's own self-mute SURVIVES (only the moderator layer is the AFK policy's),
        // so her union stays muted — the published state tells the two layers apart;
        // bob's lift publishes a fully open mouth. Re-seeds add their own pushes, so
        // assert on each member's LAST published state.
        ArgumentCaptor<VoiceControlState> states = ArgumentCaptor.forClass(VoiceControlState.class);
        verify(messagingTemplate, org.mockito.Mockito.atLeast(5)).convertAndSend(eq(VOICE_STATE_TOPIC), states.capture());
        VoiceControlState aliceLift = lastStateFor(states.getAllValues(), "alice");
        VoiceControlState bobLift = lastStateFor(states.getAllValues(), "bob");
        assertTrue(aliceLift.selfMuted());
        assertFalse(aliceLift.modMuted());
        assertTrue(aliceLift.muted()); // the union keeps her own self-mute
        assertFalse(bobLift.muted());
    }

    /** The member's last published voice state — what a client would be left holding. */
    private static VoiceControlState lastStateFor(java.util.List<VoiceControlState> states, String username) {
        return states.stream()
                .filter(state -> state.username().equals(username))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    @Test
    void switchingChannelsPublishesLeftThenJoined() {
        tracker.enter("s1", "alice", CHANNEL_A); // JOINED A
        tracker.enter("s1", "alice", CHANNEL_B); // LEFT A + JOINED B

        List<VoiceMemberEvent> events = captureEvents(3);
        assertEquals(VoiceMemberStatus.JOINED, events.get(0).status());
        assertEquals(CHANNEL_A, events.get(0).channelId());
        assertEquals(VoiceMemberStatus.LEFT, events.get(1).status());
        assertEquals(CHANNEL_A, events.get(1).channelId());
        assertEquals(VoiceMemberStatus.JOINED, events.get(2).status());
        assertEquals(CHANNEL_B, events.get(2).channelId());
        assertTrue(usernamesIn(CHANNEL_A).isEmpty());
        assertEquals(List.of("alice"), usernamesIn(CHANNEL_B));
    }

    @Test
    void disconnectRemovesMemberAndPublishesLeftIfLast() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.onDisconnect(disconnect("s1"));

        List<VoiceMemberEvent> events = captureEvents(2);
        assertEquals(VoiceMemberStatus.LEFT, events.get(1).status());
        assertTrue(tracker.snapshot().isEmpty());
    }

    @Test
    void duplicateDisconnectIsIgnored() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.onDisconnect(disconnect("s1"));
        tracker.onDisconnect(disconnect("s1")); // Spring can fire this twice — must be a no-op

        verify(messagingTemplate, times(2)).convertAndSend(eq(VOICE_TOPIC), any(VoiceMemberEvent.class));
        assertTrue(tracker.snapshot().isEmpty());
    }

    @Test
    void snapshotReflectsCurrentMembersSorted() {
        tracker.enter("s3", "carol", CHANNEL_A);
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.enter("s2", "bob", CHANNEL_B);

        List<VoiceChannelMembersDto> snapshot = tracker.snapshot().stream()
                .sorted(Comparator.comparing(VoiceChannelMembersDto::channelId))
                .toList();
        assertEquals(List.of(
                new VoiceChannelMembersDto(CHANNEL_A, List.of("alice", "carol")),
                new VoiceChannelMembersDto(CHANNEL_B, List.of("bob"))), snapshot);
    }

    @Test
    void setMutedPublishesStateAndReflectsFlag() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.setMuted(CHANNEL_A, "alice", true);
        tracker.setMuted(CHANNEL_A, "alice", false);

        List<VoiceControlState> states = captureStates(2);
        assertEquals("alice", states.get(0).username());
        assertEquals(CHANNEL_A, states.get(0).channelId());
        assertTrue(states.get(0).muted());
        assertFalse(states.get(0).deafened());
        assertFalse(states.get(1).muted());
    }

    @Test
    void kickExitsTheTargetsSessionsOnly() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.enter("s2", "bob", CHANNEL_A);

        tracker.kick(CHANNEL_A, "alice"); // alice removed, bob stays

        assertEquals(List.of("bob"), usernamesIn(CHANNEL_A));
        List<VoiceMemberEvent> events = captureEvents(3); // JOINED alice, JOINED bob, LEFT alice
        VoiceMemberEvent left = events.get(2);
        assertEquals(VoiceMemberStatus.LEFT, left.status());
        assertEquals("alice", left.username());
    }

    @Test
    void leavingDropsSelfTogglesButKeepsModeratorState() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.setMuted(CHANNEL_A, "alice", true);     // moderator — must SURVIVE the leave
        tracker.setSelfMuted(CHANNEL_A, "alice", true); // self — resets on rejoin
        tracker.exit("s1");

        assertTrue(tracker.isMuted(CHANNEL_A, "alice"), "a server-mute outlives the target rejoining");

        // The rejoin republishes the surviving moderator state (compliance + everyone's marks).
        tracker.enter("s2", "alice", CHANNEL_A);
        List<VoiceControlState> states = captureStates(3); // mod-mute, self-mute, rejoin republish
        VoiceControlState republished = states.get(2);
        assertTrue(republished.muted());
        assertTrue(republished.modMuted());
        assertFalse(republished.selfMuted(), "the self toggle reset with the leave");
    }

    @Test
    void joinReseedsTheWholeChannelsMarksForTheJoiner() {
        // Live report 2026-09-17: a muted member's badge vanished for the JOINING viewer —
        // voice.state is push-only, so a joiner that never saw the original push had no source
        // of current marks. Every enter now re-publishes EVERY marked member of the channel.
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.setSelfMuted(CHANNEL_A, "alice", true); // alice sits muted
        tracker.enter("s2", "bob", CHANNEL_B);
        tracker.setMuted(CHANNEL_B, "bob", true);       // marked, but a DIFFERENT channel

        tracker.enter("s3", "carol", CHANNEL_A);        // carol joins alice's room

        List<VoiceControlState> states = captureStates(3); // alice self-mute, bob mod-mute, reseed
        VoiceControlState reseed = states.get(2);
        assertEquals("alice", reseed.username(), "the JOINER hears alice's current marks");
        assertTrue(reseed.selfMuted());
        assertEquals(CHANNEL_A, reseed.channelId());
    }

    @Test
    void selfUnmuteDoesNotLiftModeratorMute() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.setMuted(CHANNEL_A, "alice", true);    // moderator mute
        tracker.setSelfMuted(CHANNEL_A, "alice", true);
        tracker.setSelfMuted(CHANNEL_A, "alice", false); // self-unmute — the mod mute must survive

        assertTrue(tracker.isMuted(CHANNEL_A, "alice"));
        List<VoiceControlState> states = captureStates(3);
        assertTrue(states.get(2).muted(), "the union stays muted after a self-unmute");
        assertFalse(states.get(2).selfMuted(), "the origin flags tell the client it is a moderator mute");
    }

    @Test
    void selfAndModeratorMuteCombineAndClearTogether() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.setSelfMuted(CHANNEL_A, "alice", true);
        tracker.setMuted(CHANNEL_A, "alice", true);
        tracker.setMuted(CHANNEL_A, "alice", false);   // mod lift — self still holds
        assertTrue(tracker.isMuted(CHANNEL_A, "alice"));
        tracker.setSelfMuted(CHANNEL_A, "alice", false); // last origin cleared — fully unmuted

        List<VoiceControlState> states = captureStates(4);
        // Both origins held simultaneously: the explicit mod flag must survive alongside self —
        // a union-minus-self derivation is exactly here ambiguous (the bug this pins).
        assertTrue(states.get(1).modMuted(), "the moderator flag is explicit while both hold");
        assertTrue(states.get(1).selfMuted(), "...alongside the self flag");
        assertTrue(states.get(2).muted(), "mod lift publishes the still-muted union");
        assertTrue(states.get(2).selfMuted(), "...and names the surviving origin as self");
        assertFalse(states.get(2).modMuted(), "the moderator origin is gone after its lift");
        assertFalse(states.get(3).muted(), "the final clear publishes muted=false");
        assertFalse(tracker.isMuted(CHANNEL_A, "alice"));
    }

    @Test
    void leavingDropsSelfDeafenButKeepsModeratorDeafen() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.setSelfDeafened(CHANNEL_A, "alice", true);
        tracker.setDeafened(CHANNEL_A, "alice", true);
        tracker.exit("s1");
        assertTrue(tracker.isDeafened(CHANNEL_A, "alice"), "a server-deafen outlives the leave");
        // The self toggle dropped with the leave.
        tracker.enter("s2", "alice", CHANNEL_A);
        List<VoiceControlState> states = captureStates(3); // self, mod, rejoin republish
        assertTrue(states.get(2).deafened());
        assertTrue(states.get(2).modDeafened());
        assertFalse(states.get(2).selfDeafened());
    }

    @Test
    void deafenImpliesMuteAndUndeafenClearsOnlyTheImpliedPart() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.setSelfDeafened(CHANNEL_A, "alice", true);   // implied self-mute arrives with it
        assertTrue(tracker.isMuted(CHANNEL_A, "alice"));

        tracker.setSelfMuted(CHANNEL_A, "alice", true);      // an EXPLICIT self-mute on top
        tracker.setSelfDeafened(CHANNEL_A, "alice", false);  // undeafen — only the implied part lifts
        assertTrue(tracker.isMuted(CHANNEL_A, "alice"), "the explicit mute survives the undeafen");

        tracker.setSelfMuted(CHANNEL_A, "alice", false);
        assertFalse(tracker.isMuted(CHANNEL_A, "alice"));

        List<VoiceControlState> states = captureStates(4);
        assertTrue(states.get(0).muted(), "the deafen publishes an already-muted union");
        assertTrue(states.get(0).selfMuted(), "...with the implied mute attributed to the self origin");
        assertTrue(states.get(0).deafened());
        assertTrue(states.get(2).muted(), "after the undeafen the union stays muted (explicit self-mute)");
        assertFalse(states.get(2).deafened());
    }

    @Test
    void selfDeafenImpliedMuteDropsWithTheLeave() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.setSelfDeafened(CHANNEL_A, "alice", true);
        assertTrue(tracker.isMuted(CHANNEL_A, "alice"));
        tracker.exit("s1");
        assertFalse(tracker.isMuted(CHANNEL_A, "alice"), "the implied self-mute dies with its deafen");
    }

    @Test
    void moderatorDeafenImpliedMuteSurvivesRejoin() {
        tracker.enter("s1", "alice", CHANNEL_A);
        tracker.setDeafened(CHANNEL_A, "alice", true);
        assertTrue(tracker.isMuted(CHANNEL_A, "alice"), "a server-deafen mutes the mic too");
        tracker.exit("s1");
        tracker.enter("s2", "alice", CHANNEL_A);

        assertTrue(tracker.isMuted(CHANNEL_A, "alice"), "the implied moderator mute outlives the rejoin");
        List<VoiceControlState> states = captureStates(2); // deafen, rejoin republish
        VoiceControlState republished = states.get(1);
        assertTrue(republished.muted());
        assertTrue(republished.modMuted(), "the rejoin republish carries the implied mute as mod-origin");
        assertTrue(republished.deafened());
        assertTrue(republished.modDeafened());
    }

    // --- helpers ---

    private List<VoiceMemberEvent> captureEvents(int expectedCount) {
        ArgumentCaptor<VoiceMemberEvent> captor = ArgumentCaptor.forClass(VoiceMemberEvent.class);
        verify(messagingTemplate, times(expectedCount)).convertAndSend(eq(VOICE_TOPIC), captor.capture());
        return captor.getAllValues();
    }

    private List<VoiceControlState> captureStates(int expectedCount) {
        ArgumentCaptor<VoiceControlState> captor = ArgumentCaptor.forClass(VoiceControlState.class);
        verify(messagingTemplate, times(expectedCount)).convertAndSend(eq(VOICE_STATE_TOPIC), captor.capture());
        return captor.getAllValues();
    }

    private List<String> usernamesIn(Long channelId) {
        return tracker.snapshot().stream()
                .filter(room -> room.channelId().equals(channelId))
                .map(VoiceChannelMembersDto::usernames)
                .findFirst()
                .orElse(List.of());
    }

    private static SessionDisconnectEvent disconnect(String sessionId) {
        // The tracker keys disconnect handling by session id only, so the user on the frame is
        // irrelevant; "unused" mirrors that it is never read (same convention as PresenceTrackerTest).
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setLeaveMutable(true); // keep headers mutable so session id sticks (as in production)
        accessor.setSessionId(sessionId);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(new Object(), message, sessionId, CloseStatus.NORMAL);
    }
}

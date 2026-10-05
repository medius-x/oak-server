package oak.server.service;

import oak.server.entity.User;
import oak.server.repository.RelationshipRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The M1 recipient gate: self/unknown/banned/removed targets, blocks in either direction,
 * RESTRICT_PRIVATE_MESSAGES on either side, and the two rolling rate windows.
 *
 * <p>Every stub goes through ONE staged pair (the same User instances for the lookups and the
 * relationship checks — {@code User} has no value equality, so Mockito matching is
 * reference-based).
 */
@ExtendWith(MockitoExtension.class)
class DirectContactGateTest {

    @Mock
    private AuthorizationService auth;

    @Mock
    private RelationshipRepository relationshipRepository;

    @InjectMocks
    private DirectContactGate gate;

    private record Stage(User sender, User target) {
    }

    @Test
    void allowsTwoOrdinaryMembers() {
        staged("alice", "bob");
        assertTrue(gate.canReach("alice", "bob", false));
        assertTrue(gate.canReach("alice", "bob", true));
    }

    @Test
    void rejectsSelfAndUnknownTargets() {
        staged("alice", "bob");
        assertFalse(gate.canReach("alice", "alice", false));

        when(auth.findUser("ghost")).thenReturn(Optional.empty());
        assertFalse(gate.canReach("alice", "ghost", false));
    }

    @Test
    void rejectsBannedAndRemovedTargets() {
        Stage bannedPair = staged("alice", "banned");
        bannedPair.target().setBanned(true);
        assertFalse(gate.canReach("alice", "banned", false));

        Stage removedPair = staged("alice", "removed");
        removedPair.target().setRemoved(true);
        assertFalse(gate.canReach("alice", "removed", false));
    }

    @Test
    void rejectsBlockedPairs() {
        Stage pair = staged("alice", "bob");
        when(relationshipRepository.blockedEitherDirection(pair.sender(), pair.target())).thenReturn(true);
        assertFalse(gate.canReach("alice", "bob", false));
    }

    @Test
    void dmRestrictionDoesNotGateCalls() {
        // The permission round's user decision: friend requests and 1:1 CALLS stay outside
        // RESTRICT_PRIVATE_MESSAGES (a live smoke caught the first cut silencing every call
        // between members carrying the bit — the restriction lives in DmService only).
        staged("alice", "bob");
        assertTrue(gate.canReach("alice", "bob", true));
        org.mockito.Mockito.verify(auth, org.mockito.Mockito.never())
                .dmRestricted(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void ringFramesAreTightRateCappedWhileIceFlowsFreely() {
        staged("spammer", "bob");
        for (int ring = 0; ring < DirectContactGate.RING_WINDOW_CAP; ring++) {
            assertTrue(gate.canReach("spammer", "bob", true));
        }
        assertFalse(gate.canReach("spammer", "bob", true), "the 11th RING in the window is spam");
        // Non-card frames (ICE/OFFER/ANSWER) still pass well past the ring cap.
        for (int ice = 0; ice < 50; ice++) {
            assertTrue(gate.canReach("spammer", "bob", false));
        }
        // The general window caps too — but far above any legitimate call's volume.
        staged("flooder", "bob");
        for (int frame = 0; frame < DirectContactGate.SIGNAL_WINDOW_CAP; frame++) {
            assertTrue(gate.canReach("flooder", "bob", false));
        }
        assertFalse(gate.canReach("flooder", "bob", false));
    }

    /** Stubs the ordinary-member lookup pair on stable instances for further stubbing.
     *  Lenient: negative-path tests short-circuit before some checks run (a banned target
     *  never reaches the block lookup), and STRICT_STUBS would flag the unused fixtures. */
    private Stage staged(String senderName, String targetName) {
        User sender = new User(senderName, "hash");
        User target = new User(targetName, "hash");
        org.mockito.Mockito.lenient().when(auth.findUser(senderName)).thenReturn(Optional.of(sender));
        org.mockito.Mockito.lenient().when(auth.findUser(targetName)).thenReturn(Optional.of(target));
        org.mockito.Mockito.lenient()
                .when(relationshipRepository.blockedEitherDirection(sender, target)).thenReturn(false);
        return new Stage(sender, target);
    }
}

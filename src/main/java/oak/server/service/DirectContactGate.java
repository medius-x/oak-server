package oak.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import oak.server.entity.User;
import oak.server.repository.RelationshipRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The M1 recipient gate for the two 1:1 push channels ({@code /app/call.signal} and
 * {@code /app/voice.invite}): before the server relays a frame to a per-user queue, the
 * RECIPIENT must be reachable — previously {@code to} was used verbatim, so any member could
 * ring/invite ANY user (harassment spam), including users who BLOCKED them. The gate
 * enforces, in order:
 * <ul>
 *   <li>per-sender rate limits (a generous one for all signaling — ICE candidates arrive in
 *       bursts of dozens — and a tight one for card-pushing frames: RINGs and voice invites);</li>
 *   <li>not self, target exists, target neither banned nor removed;</li>
 *   <li>no block in either direction.</li>
 * </ul>
 * <b>Deliberately NOT gated: RESTRICT_PRIVATE_MESSAGES.</b> The permission round's user
 * decision (2026-10-05) keeps friend requests and 1:1 CALLS outside the DM restriction —
 * a live smoke caught the first cut gating them (a role test with the bit made every call
 * silent for the callee). The restriction lives in DmService/MessageService only.
 *
 * <p>Silent false at the call sites (the call.signal discipline: a probing client learns
 * nothing from a drop) — but every refusal is INFO-logged here with its reason, so a
 * "why is it quiet" investigation reads straight off the server log.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DirectContactGate {

    /** All signaling frames per sender per window — ICE bursts must fit comfortably. */
    static final long SIGNAL_WINDOW_MILLIS = 10 * 60 * 1000L;
    static final int SIGNAL_WINDOW_CAP = 300;
    /** Card-pushing frames (RING / voice invite) per sender per window. */
    static final long RING_WINDOW_MILLIS = 10 * 60 * 1000L;
    static final int RING_WINDOW_CAP = 10;

    private final AuthorizationService auth;
    private final RelationshipRepository relationshipRepository;

    private final Map<String, Deque<Long>> signalWindows = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> ringWindows = new ConcurrentHashMap<>();

    /** Whether the server may relay a personal frame from sender to target.
     *  {@code cardPush} marks frames that make the target's UI ring (RING, voice invite). */
    public boolean canReach(String senderUsername, String targetUsername, boolean cardPush) {
        if (senderUsername == null || targetUsername == null || senderUsername.equals(targetUsername)) {
            log.info("[contact-gate] {} → {}: refused (self or malformed target)", senderUsername, targetUsername);
            return false;
        }
        if (!tryAcquire(signalWindows, senderUsername, SIGNAL_WINDOW_MILLIS, SIGNAL_WINDOW_CAP)) {
            log.info("[contact-gate] {} → {}: refused (signal rate limit)", senderUsername, targetUsername);
            return false;
        }
        if (cardPush && !tryAcquire(ringWindows, senderUsername, RING_WINDOW_MILLIS, RING_WINDOW_CAP)) {
            log.info("[contact-gate] {} → {}: refused (ring rate limit)", senderUsername, targetUsername);
            return false;
        }
        User sender = auth.findUser(senderUsername).orElse(null);
        User target = auth.findUser(targetUsername).orElse(null);
        if (sender == null || target == null || target.isBanned() || target.isRemoved()) {
            log.info("[contact-gate] {} → {}: refused (unknown/banned/removed target)", senderUsername, targetUsername);
            return false;
        }
        if (relationshipRepository.blockedEitherDirection(sender, target)) {
            log.info("[contact-gate] {} → {}: refused (blocked pair)", senderUsername, targetUsername);
            return false;
        }
        return true;
    }

    /** Rolling-window limiter keyed by sender (same deque shape as AttachmentSlotRegistry). */
    private static boolean tryAcquire(Map<String, Deque<Long>> windows, String sender,
                                      long windowMillis, int cap) {
        long timestamp = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(sender, ignored -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && timestamp - window.peekFirst() >= windowMillis) {
                window.pollFirst();
            }
            if (window.size() >= cap) {
                return false;
            }
            window.addLast(timestamp);
            return true;
        }
    }
}

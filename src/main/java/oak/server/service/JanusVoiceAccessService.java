package oak.server.service;

/**
 * Voice-room access control against the Janus SFU, plus the FORCED layer of moderator actions that
 * works even against a rogue client ignoring the cooperative STOMP pushes.
 *
 * <p>Security model (Janus runs with {@code token_auth = true} and rooms carry a pin + admin secret):
 * <ul>
 *   <li><b>token</b> — per (user, room-visit), minted through the Admin API and valid for the videoroom
 *       plugin only; without one, no request to Janus is accepted at all (closes the "anyone who can
 *       reach the host joins/listens" hole).</li>
 *   <li><b>pin</b> — deterministic per-channel HMAC handed to clients in the /room response; required
 *       by {@code join} (closes the "any authenticated user enters ANY room by id brute-force" hole —
 *       tokens are plugin-scoped, not room-scoped).</li>
 *   <li><b>secret</b> — a different per-channel HMAC known ONLY to the server; required by the room's
 *       admin requests ({@code kick}/{@code moderate}) so nobody but oak-server can moderate.</li>
 * </ul>
 *
 * <p>Test profile swaps the implementation for a noop ({@code oak.janus.admin-enabled=false}).
 */
public interface JanusVoiceAccessService {

    /** Registers a fresh Janus access token for the user. Throws {@link JanusAdminException} if the gateway is down. */
    String issueVoiceToken(String username, long channelId);

    /** Join pin of the channel's videoroom — deterministic, never stored, handed to clients by RoomController. */
    String roomPin(long channelId);

    /** Creates the channel's videoroom (room id = channel id) with the pin + admin secret if absent. */
    void ensureRoom(long channelId);

    /** Revokes the user's tokens for the channel — a kick follow-up blocking re-entry until a fresh /room call. */
    void revokeTokens(long channelId, String username);

    /** Force-removes every Janus publisher of the user from the room (all sessions — multi-tab), tearing down media instantly. */
    void forceKick(long channelId, String username);

    /** Server-side mutes (or unmutes) the user's outgoing audio — {@code moderate} with {@code mute=true/false}. */
    void moderateAudio(long channelId, String username, boolean mute);
}

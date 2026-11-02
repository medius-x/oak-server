package oak.server.service;

/**
 * The gateway's videoroom plugin answered {@code 426 No such room} to a control join — the
 * room this server's registries still list members in no longer exists on the gateway
 * (destroyed out from under us: gateway restart, or a destroy our bookkeeping missed).
 * Fundamentally different from a silent transport failure: the correct reaction is registry
 * healing (evict the stale members), not a retry next tick — which, before this verdict
 * existed, produced "control join produced no joined event" WARNs every sweep minute for a
 * whole day (live report 2026-10-30).
 */
public class VoiceRoomVanishedException extends RuntimeException {

    public VoiceRoomVanishedException(long channelId) {
        super("voice room " + channelId + " no longer exists on the gateway (janus 426)");
    }
}

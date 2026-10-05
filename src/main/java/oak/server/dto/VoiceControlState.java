package oak.server.dto;

/**
 * Server-side mute/deafen state for one member in a voice room, broadcast to {@code /topic/voice.state}.
 * Every client uses it to paint the server-muted/deafened indicator on that member's row; the affected
 * member (matching {@code username}) additionally complies — forces its mic/speaker to the state.
 *
 * <p>Both ORIGINS are carried explicitly ({@code selfMuted}/{@code modMuted}, deafened likewise) —
 * a client-side {@code mod = union ∖ self} derivation is AMBIGUOUS when both hold (union=true,
 * self=true leaves mod unknowable), which lost the moderator's flag whenever the member was also
 * self-muted. {@code muted}/{@code deafened} remain the union (the compliance semantics; union is
 * derivable, kept for readability/compat). The ninth field {@code permMuted} is the SPEAK-permission
 * mute (security round 6): a member sitting in a channel without {@code Permission#SPEAK} — it paints
 * the same red as a moderator mute, but neither a self-unmute nor a moderator's Unmute lifts it;
 * only the permission sweep does. WIRE: fields are append-only; both mirrors change in one pass
 * (the client mappers do not tolerate unknown fields — paired deploy).
 */
public record VoiceControlState(String username, Long channelId, boolean muted, boolean deafened,
                                boolean selfMuted, boolean selfDeafened,
                                boolean modMuted, boolean modDeafened,
                                boolean permMuted) {
}

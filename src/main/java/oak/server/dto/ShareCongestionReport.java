package oak.server.dto;

/**
 * {@code SEND /app/share.congestion} — a viewer's congestion verdict about
 * ANOTHER user's screen share (2026-11-11, the client's 8-Mbps freeze
 * post-mortem). The publisher's own stats cannot see downstream loss (Janus
 * vanilla-forwarding feeds it no transport-cc feedback), so the server relays
 * the viewer's number into the publisher's user queue, where the publisher's
 * adaptive bitrate ladder consumes it.
 *
 * <p>{@code reporter} is stamped SERVER-side on the relayed copy (the wire
 * report never carries it); the publisher's client reads only {@code lostPct}.
 */
public record ShareCongestionReport(String publisher, Double lostPct, String reporter) {
}

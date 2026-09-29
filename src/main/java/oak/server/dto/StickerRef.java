package oak.server.dto;

/**
 * The sticker a message carries — the DENORMALIZED copy stamped at send time
 * ({@code sticker_name} + {@code sticker_file_key} off the message row). No catalog join
 * happens at response time; when the catalog row is later deleted, old messages keep this
 * ref and the presigned url dies with the swept object (the client renders a tombstone).
 */
public record StickerRef(String name, String key, String url) {
}

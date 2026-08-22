package oak.server.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Forward-destination write body for {@code POST /api/channels/{targetChannelId}/messages/forward}.
 * Two mutually exclusive modes:
 *
 * <ul>
 *   <li><b>Intra-instance</b> ({@code sourceMessageId != null}): the server loads the source
 *   message of THIS instance and copies content/attachment itself, deriving every origin field
 *   canonically (server profile name, source channel name, source author, source creation time).
 *   Client-supplied origin fields are ignored. The actor must be able to read the source channel
 *   ({@code READ_MESSAGE_HISTORY}) and write the target ({@code SEND_MESSAGES}).
 *   {@code ForwardInfo.trusted} comes back true.</li>
 *   <li><b>Cross-instance snapshot</b> ({@code sourceMessageId == null}): for a message that lives
 *   on a DIFFERENT OAK instance this server cannot reach, the client supplies the copied payload
 *   and the origin fields verbatim. These are accepted on trust (federation model, like email
 *   {@code From:}) — the instance has no way to verify them; {@code ForwardInfo.trusted} comes
 *   back false and clients mark such cards "unverified".</li>
 * </ul>
 *
 * <p>The attachment is shared, not re-uploaded: an intra-instance forward reuses the source's
 * {@code attachmentKey} (MinIO objects are never deleted with messages), a cross-instance one
 * carries a key the client re-uploaded to THIS instance beforehand.
 */
public record ForwardMessageRequest(
        Long sourceMessageId,
        @Size(max = 4000) String content,
        @Size(max = 512) String attachmentKey,
        @Size(max = 255) String attachmentFileName,
        @Size(max = 100) String originServer,
        @Size(max = 100) String originChannel,
        Long originAuthorId,
        @Size(max = 50) String originAuthorName,
        Instant originCreatedAt
) {
}

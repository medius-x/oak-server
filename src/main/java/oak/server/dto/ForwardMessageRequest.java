package oak.server.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

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
 * <p>The attachments are shared, not re-uploaded: an intra-instance forward reuses ALL of the
 * source's MinIO keys (objects are never deleted with messages), a cross-instance one carries
 * keys the client re-uploaded to THIS instance beforehand. At most
 * {@code MessageService.MAX_ATTACHMENTS} per forward.
 */
public record ForwardMessageRequest(
        Long sourceMessageId,
        @Size(max = 4000) String content,
        @Size(max = 10) List<AttachmentRef> attachments,
        @Size(max = 100) String originServer,
        @Size(max = 100) String originChannel,
        Long originAuthorId,
        @Size(max = 50) String originAuthorName,
        Instant originCreatedAt,
        ReplyInfo originReply
) {
}

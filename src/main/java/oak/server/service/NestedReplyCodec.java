package oak.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import oak.server.dto.ForwardInfo;
import oak.server.dto.ReplyInfo;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes the nested quote tree of a forwarded reply to/from the {@code forward_nested_reply}
 * JSON column — the project's first JSON column. Isolated here so the Jackson-generation question
 * (Boot 4 wires Jackson 3, {@code tools.jackson}, while jjwt pulls Jackson 2 for its own runtime)
 * lives in exactly one file: this component injects the Boot {@link ObjectMapper} bean, the same
 * mapper the REST layer uses, so the column's format can never drift from the wire's.
 *
 * <p><b>Lenient by design.</b> Jackson 3 does not fail on unknown properties by default, so a
 * future field added to {@code ReplyInfo} keeps OLD rows readable (the lesson the client's
 * {@code ServerConnectionStore} learned the hard way). Both directions degrade instead of
 * throwing: a read failure returns null (the card renders without its nesting), a write failure
 * returns null (the forward persists without the snapshot) — the nested quote is cosmetic and
 * must never fail a forward.
 *
 * <p>{@link #capDepth} bounds the tree BEFORE persisting: a hostile or iteratively-grown
 * "reply-to-a-forward, then forward that reply" chain would otherwise embed the previous
 * snapshot every generation and grow the column without limit.
 */
@Slf4j
@Component
public class NestedReplyCodec {

    private final ObjectMapper mapper;

    public NestedReplyCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Serializes the tree, or null when {@code tree} is null / unserializable (logged). */
    public String write(ReplyInfo tree) {
        if (tree == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(tree);
        } catch (RuntimeException failure) {
            log.warn("Could not serialize a nested reply snapshot: {}", failure.getMessage());
            return null;
        }
    }

    /** Parses the stored snapshot; null for a blank column or an unreadable payload (logged). */
    public ReplyInfo read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, ReplyInfo.class);
        } catch (RuntimeException failure) {
            log.warn("Could not parse a nested reply snapshot: {}", failure.getMessage());
            return null;
        }
    }

    /** Rebuilds the tree truncated at {@code maxDepth} nested {@code ReplyInfo} cards, counting
     *  BOTH edges ({@code reply} and {@code forward.reply}) — everything past the cap is cut,
     *  not marked deleted. Returns null when the whole tree is past the cap. */
    public ReplyInfo capDepth(ReplyInfo node, int maxDepth) {
        if (node == null || maxDepth <= 0) {
            return null;
        }
        ForwardInfo forward = node.forward();
        ForwardInfo cappedForward = forward == null ? null
                : new ForwardInfo(forward.serverName(), forward.channelName(), forward.authorId(),
                        forward.authorUsername(), forward.createdAt(), forward.trusted(),
                        capDepth(forward.reply(), maxDepth - 1));
        return new ReplyInfo(node.messageId(), node.authorId(), node.authorUsername(),
                node.preview(), node.createdAt(), node.deleted(), cappedForward,
                capDepth(node.reply(), maxDepth - 1));
    }
}

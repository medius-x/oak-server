package oak.server.service;

import oak.server.dto.ForwardInfo;
import oak.server.dto.ReplyInfo;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The {@code forward_nested_reply} JSON column codec: round-trip, lenient reads (a future
 *  {@code ReplyInfo} field must not brick existing rows), null/degraded paths, and the depth
 *  cap that bounds every persisted snapshot. */
class NestedReplyCodecTest {

    /** The same mapper family Boot wires (Jackson 3, tools.jackson) — built directly so the test
     *  stays a plain unit test with no Spring context. */
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final NestedReplyCodec codec = new NestedReplyCodec(mapper);

    private static final Instant STAMP = Instant.parse("2026-01-01T10:00:00Z");

    /** A reply chain of {@code depth} cards (1 = a single card, no nesting). */
    private static ReplyInfo chain(int depth) {
        if (depth <= 0) {
            return null;
        }
        return new ReplyInfo((long) depth, (long) depth * 10, "user" + depth, "preview " + depth,
                STAMP, false, null, chain(depth - 1));
    }

    private static int depthOf(ReplyInfo node) {
        int depth = 0;
        while (node != null) {
            depth++;
            node = node.reply();
        }
        return depth;
    }

    @Test
    void roundTripsANestedTree() {
        ReplyInfo tree = new ReplyInfo(1L, 10L, "alice", "outer", STAMP, false,
                new ForwardInfo("Srv", "chan", 20L, "bob", STAMP, true, chain(2)),
                chain(3));
        ReplyInfo back = codec.read(codec.write(tree));
        assertEquals(tree, back);   // record equality walks the whole tree, forward.reply included
    }

    @Test
    void readToleratesUnknownFields() {
        String json = """
                {"messageId":1,"authorId":2,"authorUsername":"alice","preview":"p",
                 "createdAt":"2026-01-01T10:00:00Z","deleted":false,
                 "someFutureField":{"nested":[1,2]},"reply":null}
                """;
        ReplyInfo parsed = codec.read(json);
        assertNotNull(parsed);
        assertEquals("alice", parsed.authorUsername());
        assertNull(parsed.reply());
    }

    @Test
    void nullAndGarbageDegradeToNull() {
        assertNull(codec.write(null));
        assertNull(codec.read(null));
        assertNull(codec.read("  "));
        assertNull(codec.read("this is not json"));
    }

    @Test
    void capDepthTruncatesThroughBothEdges() {
        assertEquals(5, depthOf(codec.capDepth(chain(9), 5)));
        assertNull(codec.capDepth(chain(9), 0));
        assertNull(codec.capDepth(null, 5));
        // The forward.reply edge counts toward the same budget: a card at depth 1 whose forward
        // context carries a 9-deep chain keeps only 4 levels of it after the cap.
        ReplyInfo withForward = new ReplyInfo(1L, 10L, "alice", "outer", STAMP, false,
                new ForwardInfo("Srv", "chan", 20L, "bob", STAMP, false, chain(9)), null);
        ReplyInfo capped = codec.capDepth(withForward, 5);
        assertEquals(4, depthOf(capped.forward().reply()));
        assertNull(capped.reply());
    }
}

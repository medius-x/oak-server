package oak.server.service;

import oak.server.dto.AttachmentRef;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code messages.attachments} JSON column codec: round-trip, the never-null read contract
 *  (blank column and unreadable payload both degrade to an EMPTY list — the migration's NULL
 *  rows and a future field must not brick a message), and the null write. */
class AttachmentListCodecTest {

    /** The same mapper family Boot wires (Jackson 3, tools.jackson) — built directly so the test
     *  stays a plain unit test with no Spring context. */
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AttachmentListCodec codec = new AttachmentListCodec(mapper);

    @Test
    void roundTripsAnAttachmentList() {
        List<AttachmentRef> attachments = List.of(
                new AttachmentRef("attachments/abc/pic.png", "pic.png"),
                new AttachmentRef("attachments/abc/report.pdf", "report.pdf"));

        List<AttachmentRef> back = codec.read(codec.write(attachments));

        assertEquals(attachments, back);
    }

    @Test
    void emptyListWritesToEmptyJsonArrayAndBack() {
        assertEquals(List.of(), codec.read(codec.write(List.of())));
    }

    @Test
    void nullWriteYieldsNull() {
        assertNull(codec.write(null));
    }

    @Test
    void blankColumnReadsAsEmptyList() {
        assertEquals(List.of(), codec.read(null));
        assertEquals(List.of(), codec.read(""));
        assertEquals(List.of(), codec.read("   "));
    }

    @Test
    void unreadablePayloadReadsAsEmptyList() {
        assertEquals(List.of(), codec.read("not json at all"));
    }

    @Test
    void unknownFieldIsToleratedNotFatal() {
        // A future AttachmentRef field on the wire must not brick existing rows (Jackson 3 does
        // not fail on unknown properties by default — the lenient-read discipline).
        List<AttachmentRef> back = codec.read(
                "[{\"key\":\"attachments/abc/pic.png\",\"fileName\":\"pic.png\",\"futureField\":42}]");
        assertEquals(1, back.size());
        assertEquals("attachments/abc/pic.png", back.get(0).key());
        assertEquals("pic.png", back.get(0).fileName());
    }

    @Test
    void readNeverReturnsNull() {
        for (String column : new String[] {null, "", "junk"}) {
            assertTrue(codec.read(column) != null, "read must never return null for: " + column);
        }
    }
}

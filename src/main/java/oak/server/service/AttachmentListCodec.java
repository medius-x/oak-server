package oak.server.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import oak.server.dto.AttachmentRef;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Serializes a message's attachment list to/from the {@code messages.attachments} JSON column —
 * the second JSON column of the project (see {@link NestedReplyCodec} for the discipline).
 * Injects the Boot {@link ObjectMapper} bean (Jackson 3, {@code tools.jackson}), the same
 * mapper the REST layer uses, so the column's field names can never drift from the wire's
 * {@link AttachmentRef}.
 *
 * <p><b>Lenient by design.</b> Jackson 3 does not fail on unknown properties by default, so a
 * future field added to {@code AttachmentRef} keeps OLD rows readable. Both directions degrade
 * instead of throwing: a read failure returns an EMPTY list (a blank column is simply "no
 * attachments" — null would force null-checks on every consumer), a write failure returns null
 * + log (the same degradation {@code NestedReplyCodec} applies to its cosmetic snapshot; here
 * it is pathological — records serialize trivially — and the message would persist without its
 * attachments, which the content-or-attachment rule already guards upstream).
 */
@Slf4j
@Component
public class AttachmentListCodec {

    private static final TypeReference<List<AttachmentRef>> ATTACHMENT_LIST = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public AttachmentListCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Serializes the list; null when {@code attachments} is null / unserializable (logged). */
    public String write(List<AttachmentRef> attachments) {
        if (attachments == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(attachments);
        } catch (RuntimeException failure) {
            log.warn("Could not serialize an attachment list: {}", failure.getMessage());
            return null;
        }
    }

    /** Parses the stored column; an EMPTY list for a blank column or an unreadable payload
     *  (logged) — never null, so callers can branch on {@code isEmpty()} alone. */
    public List<AttachmentRef> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<AttachmentRef> parsed = mapper.readValue(json, ATTACHMENT_LIST);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (RuntimeException failure) {
            log.warn("Could not parse an attachment list: {}", failure.getMessage());
            return List.of();
        }
    }
}

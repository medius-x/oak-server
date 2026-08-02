package oak.server.dto;

/**
 * A resolved {@code @}-mention inside a message body. {@code start}/{@code end} are offsets into
 * {@link MessageResponse#content()} for the {@code @token} substring (start inclusive of the
 * {@code @}, end exclusive), so the client can color exactly that span and disambiguate two
 * occurrences of the same target.
 *
 * <p>{@code type}: {@code USER} (id = user id), {@code ROLE} (id = role id), {@code EVERYONE} /
 * {@code HERE} (id = null for HERE; the {@code @everyone} role id for EVERYONE). {@code name} is the
 * snapshot at parse time (the username or role name; null for EVERYONE/HERE).
 *
 * <p>Mentions are parsed on read from the plain-text {@code @token} grammar (no {@code <@id>} markers,
 * no DB column), so renaming a user/role leaves old {@code @mentions} as plain un-highlighted text —
 * a documented tradeoff for v1.
 */
public record MentionResponse(
        String type,   // USER | ROLE | EVERYONE | HERE
        Long id,
        String name,
        int start,
        int end
) {
}

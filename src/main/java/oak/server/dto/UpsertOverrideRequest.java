package oak.server.dto;

import java.util.Set;

/** Creates or replaces a channel permission override for a role. {@code allow}/{@code deny} are
 *  permission names; {@code null}/{@code empty} mean "none" (clears that side). */
public record UpsertOverrideRequest(Set<String> allow, Set<String> deny) {
}

package oak.server.dto;

import java.util.List;

/**
 * The Invites settings screen in one fetch: the current TTL setting (drives the input's
 * loaded value) plus the active-link list.
 */
public record InvitesStatusResponse(Integer ttlMinutes, List<InviteResponse> invites) {
}

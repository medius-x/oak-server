package oak.server.dto;

import java.util.List;

/**
 * The feature-flag catalogue this server reports in {@code GET /api/server} (the client-server
 * compatibility skeleton, user decision 2026-10-15). The client hides the UI of a feature whose
 * flag is absent, so a NEWER client degrades gracefully against an older server.
 *
 * <p><b>Doctrine:</b> a flag is added here ONLY for features introduced AFTER 0.1.0 — everything
 * shipped in 0.1.0 is the unconditional BASE (an old server that predates the field reports no
 * {@code capabilities} at all, and the client treats "no field" as "the base"). Never
 * backfill flags for base features: a backported or forked build without them would hide working
 * UI. A flag, once added, is never removed or renamed (clients key on the literal string).
 */
public final class ServerCapabilities {

    /** kebab-case string ids; empty while every shipped feature belongs to the 0.1.0 base. */
    public static final List<String> FEATURES = List.of();

    private ServerCapabilities() {
    }
}

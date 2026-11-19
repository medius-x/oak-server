package oak.server.dto;

import oak.server.entity.ServerProfile;
import oak.server.service.igdb.IgdbAuthService;

import java.util.ArrayList;
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

    /** kebab-case string ids; empty while every shipped feature belongs to the 0.1.0 base.
     *  {@code backups} — the full-instance backup block (build/estimate/status/download/
     *  discard + the restore runner + migration), backup round 2026-10-28: the FIRST real
     *  use of the skeleton (user call — a client connected to a 0.2.0 server must not show
     *  the block: every endpoint 404s there).
     *  {@code server-migration} — the direct server-to-server transfer leg (2026-11-02):
     *  one-shot migration token + the token-gated transfer endpoints; without the flag the
     *  client always routes the archive through the user's computer (the legacy route).
     *  {@code afk-roster} — the yellow AFK presence dot (2026-11-04): /topic/voice.afk
     *  flips + the GET /api/voice/afk snapshot; without the flag the client skips both —
     *  no idle 404 probes against an older server, dots simply stay green/grey.
     *  {@code share-congestion} — the viewer→publisher congestion relay (2026-11-11):
     *  SEND /app/share.congestion + /user/queue/share.congestion delivery, feeding the
     *  publisher's adaptive share-bitrate ladder; without the flag the client never
     *  sends the reports (the ladder still runs on PLI + RTT alone).
     *  {@code share-source-state} — the minimized-share relay (2026-11-11):
     *  SEND /app/share.source-state → /topic/share.state, letting the viewers'
     *  pop-out caption a frozen (minimized) share frame; without the flag the
     *  client skips both the reports and the topic.
     *  {@code roster-status} — the manual STATUS dimension (2026-11-17): the
     *  status menu (online/away/do-not-disturb + auto), SEND /app/roster.status,
     *  /topic/roster.status flips and the GET /api/presence/status snapshot; the
     *  legacy boolean AFK wire keeps working either way. Without the flag the
     *  client keeps the 2026-11-04 away-only model and hides the menu.
     *  {@code igdb-settings} — the IGDB SETTINGS endpoints (2026-11-19):
     *  {@code GET/PATCH /api/server/igdb} (the Twitch application credentials,
     *  MANAGE_SERVER). UNCONDITIONAL on purpose — the endpoints work with or
     *  without credentials, and the client needs the flag BEFORE any pair is
     *  set to show the settings screen at all; the DETECT side is the separate
     *  conditional {@link #IGDB_GAMES}.
     *  {@code voice-user-limit} — the VOICE room participant limit (2026-11-18):
     *  {@code ChannelResponse.userLimit} + {@code PATCH /api/channels/{id}/user-limit}
     *  + the 403 full-room gate on {@code GET /room} + the JOIN_FULL_ROOMS bypass
     *  permission (rides the regular /api/permissions catalog). Without the flag the
     *  client hides the limit editor — the 403 of an old client hitting a full room
     *  is handled by its generic error path anyway. */
    public static final List<String> FEATURES = List.of("backups", "server-migration", "afk-roster", "presence-activity", "share-congestion", "share-source-state", "roster-status", "voice-user-limit", "igdb-settings");

    /** CONDITIONAL flag — deliberately NOT a member of {@link #FEATURES}: the IGDB
     *  game auto-detect ladder (2026-11-19): {@code GET /api/games/lookup} +
     *  {@code GET /api/games/{id}} + {@code POST /api/games/confirm}. Reported
     *  by {@link #featuresFor} ONLY while both Twitch application credentials are
     *  configured: without them every /api/games endpoint answers 503, so lighting
     *  the client's auto-detect UI up would be a dead button. Like every flag, once
     *  reported the literal is frozen forever. */
    public static final String IGDB_GAMES = "igdb-games";

    /** The runtime list for {@code GET /api/server}: the unconditional {@link #FEATURES}
     *  plus the conditional flags the instance's configuration currently earns. */
    public static List<String> featuresFor(ServerProfile profile) {
        if (!IgdbAuthService.isIntegrationActive(profile)) {
            return FEATURES;
        }
        List<String> out = new ArrayList<>(FEATURES);
        out.add(IGDB_GAMES);
        return List.copyOf(out);
    }

    private ServerCapabilities() {
    }
}

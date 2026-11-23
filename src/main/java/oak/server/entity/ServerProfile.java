package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The instance's own identity: a single row (always {@code id = 1}) holding the server-stored
 * {@code name} and avatar keys. There is no guild entity — the instance <i>is</i> the server — so
 * this is a one-row singleton edited via {@code /api/server} (gated by {@code MANAGE_SERVER}) and
 * read by every client on connect ({@code GET /api/server}).
 *
 * <p>Like {@link User}, the avatar lives in MinIO behind presigned URLs; {@code avatarKey} is the
 * stable object key and {@code avatarOriginalKey} the pre-crop original kept for re-cropping.
 *
 * <p>No {@code @Data}/@ToString/@EqualsAndHashCode — JPA entity convention (matches {@link User}).
 */
@Entity
@Table(name = "server_profile")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** MinIO object key of the server icon, or null when none is set (presigned GET is signed per request). */
    @Column(name = "avatar_key", length = 512)
    private String avatarKey;

    /** MinIO object key of the original (pre-crop) icon image — lets an editor re-crop without
     *  re-selecting a file. Loaded lazily via {@code GET /api/server/avatar/original}. */
    @Column(name = "avatar_original_key", length = 512)
    private String avatarOriginalKey;

    /** Host the instance ADVERTISES for client connections — what a member types into "Add server".
     *  Storage for the future remote-provisioning flow (the setup client will push it here); seeded
     *  with a manual/test value on fresh rows, null on rows created before the field existed. */
    @Column(name = "connection_host", length = 255)
    private String connectionHost;

    /** TCP port the advertised host is reached on (e.g. 8080 — REST and WS share it). Null = not set. */
    @Column(name = "connection_port")
    private Integer connectionPort;

    /** Scheme of the advertised address ({@code http} | {@code https}; null = the historical
     *  default http). Set by a TLS deployment (the Caddy entry point): invite links carry it,
     *  and default ports (80/443) are omitted from the assembled URL. */
    @Column(name = "connection_scheme", length = 8)
    private String connectionScheme;

    // --- Storage-GC policy (the "Server" settings screen; nulls on rows predating the
    //     columns resolve to the code defaults — see StorageGcService) ---

    /** Start purging when the data filesystem is more than this percent full. */
    @Column(name = "storage_high_watermark_percent", columnDefinition = "int default 85")
    private Integer storageHighWatermarkPercent;

    /** Purge until the filesystem is back at (or below) this percent full. */
    @Column(name = "storage_low_watermark_percent", columnDefinition = "int default 50")
    private Integer storageLowWatermarkPercent;

    /** Local minute-of-day the nightly GC fires at (e.g. 210 = 03:30). */
    @Column(name = "storage_gc_minute_of_day", columnDefinition = "int default 210")
    private Integer storageGcMinuteOfDay;

    /** False = the scheduled GC never fires (manual "run now" still works; the
     *  event tier — deleting an object with its last reference — is unaffected too). */
    @Column(name = "storage_gc_enabled", columnDefinition = "boolean default true")
    private Boolean storageGcEnabled;

    /** How long a freshly minted invite link accepts registrations (minutes). Retroactive
     *  by user decision: changing it reschedules every ACTIVE invite to {@code createdAt + ttl}.
     *  Null on rows predating the column resolves to the code default (1440 = one day). */
    @Column(name = "invite_ttl_minutes", columnDefinition = "int default 1440")
    private Integer inviteTtlMinutes;

    /** The AFK sweep's target room — idle voice members are moved here after the timeout.
     *  Null (or a channel that no longer exists / is not VOICE) disables the sweep. A bare
     *  id with NO FK on purpose: the channel-deleted reset is enforced in code
     *  (ChannelService.delete), and the H2 test schema regenerates from entities anyway. */
    @Column(name = "afk_channel_id")
    private Long afkChannelId;

    /** How long a voice member may stay idle before the AFK sweep moves them (minutes).
     *  Null on rows predating the column resolves to the code default (30). */
    @Column(name = "afk_timeout_minutes", columnDefinition = "int default 30")
    private Integer afkTimeoutMinutes;

    /** Twitch application Client ID of the IGDB game-database integration (registered
     *  at dev.twitch.tv, pasted by the admin in Server Settings → IGDB). Either
     *  credential null = the integration is off: /api/games answers 503 and the
     *  {@code igdb-games} capability is not reported. Never secret — but only shown
     *  in the MANAGE_SERVER settings screen. */
    @Column(name = "igdb_client_id", length = 64)
    private String igdbClientId;

    /** The Client ID's secret twin. NEVER returned by any API endpoint (the settings
     *  response carries only the id + a configured flag) and never written to logs;
     *  lives in the DB like the rest of the instance configuration. */
    @Column(name = "igdb_client_secret", length = 128)
    private String igdbClientSecret;

    /** The integration's PAUSE switch (2026-11-19): false = the ladder answers 503
     *  and the {@code igdb-games} capability is not reported, while BOTH credentials
     *  stay stored (re-enabling restores detection with its whole match cache). Null
     *  on rows predating the column resolves to enabled. */
    @Column(name = "igdb_enabled")
    private Boolean igdbEnabled;

    /** The SteamGridDB API key (2026-11-19, V23): the square game ICONS source —
     *  IGDB has no app icons, SGDB's official-style list answers directly by the
     *  Steam appid. Write-only like the IGDB secret (the settings response carries
     *  only a configured flag); null = the icon layer is off and every game card
     *  keeps serving its IGDB {@code t_thumb} square. */
    @Column(name = "sgdb_api_key", length = 128)
    private String sgdbApiKey;

    /** The icon layer's PAUSE switch (the IGDB pattern, 2026-11-23): false =
     *  icons are neither resolved nor SERVED (cached bytes stay stored but
     *  answer nothing — the user's explicit semantics); the KEY is kept, so
     *  re-enabling restores icons instantly. Null = enabled. Pausing IGDB
     *  mutes the whole game stack (the ladder answers 503), so its switch
     *  implicitly parks this layer too — this column is the INDEPENDENT one. */
    @Column(name = "sgdb_enabled")
    private Boolean sgdbEnabled;

    /** The video codec Janus offers in voice rooms (2026-11-23, V24):
     *  {@code vp8} / {@code vp9} / {@code h264}. Null = the product default,
     *  VP9 (2026-11-24 — see ServerProfileController#effectiveVideoCodec).
     *  Applied when a room is CREATED: a live room's codec is fixed until it
     *  empties. */
    @Column(name = "video_codec", length = 16)
    private String videoCodec;

    public ServerProfile(String name) {
        this.name = name;
    }
}

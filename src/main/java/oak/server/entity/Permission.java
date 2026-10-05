package oak.server.entity;

/**
 * The full Discord-style permission catalog. Each constant owns a stable bit position
 * ({@link #bit} = {@code 1L << ordinal()}); a role's granted permissions are stored as the OR of those
 * bits in a single {@code long} field ({@code Role.permissions}), and channel overrides store
 * {@code allow}/{@code deny} the same way. Under 64 constants, so a {@code long} is enough.
 *
 * <p><b>Stability contract:</b> never reorder these constants and never insert one above another —
 * the ordinal <i>is</i> the persisted bit position, so reordering would silently remap every stored
 * role's permissions. Add new permissions ONLY by appending at the very END of the enum — inserting
 * "at the end of a group" would shift every later group's bits. Constants marked
 * {@code future} in the catalog already have a bit and a UI checkbox, but no enforcement point yet —
 * they light up as the matching feature (speak/stream enforcement, audit log …) lands.
 *
 * <p><b>Retired permissions (reserved bits):</b> a constant the instance will never enforce again is
 * renamed to a {@code RESERVED_*} placeholder <i>in place</i> (deleting it would shift every later
 * ordinal and silently remap stored masks) and flagged {@code reserved = true}. Reserved bits never
 * appear in the {@code /api/permissions} catalog nor in any wire name list. A retired bit may be
 * REPURPOSED later by renaming the constant to the new meaning — safe only while the bit is cleared
 * from every persisted mask ({@code V16__retire_reserved_permission_bits.sql} did that at retirement
 * time; a restored backup from before V16 would need the same cleanup re-run, or the stale grant
 * would silently hand the new permission to everyone).
 */
public enum Permission {

    // --- General (server-wide authority) ---
    ADMINISTRATOR(Group.GENERAL),          // bypasses every permission check (except hierarchy vs OWNER)
    MANAGE_SERVER(Group.GENERAL),          // edit instance-level identity (name/icon/connection info
                                           // via /api/server) — enforced on PATCH /api/server, its
                                           // avatar endpoints and PATCH /api/server/connection
    MANAGE_ROLES(Group.GENERAL),           // create/edit/delete/reorder roles
    MANAGE_CHANNELS(Group.GENERAL),        // create/edit/delete/reorder channels + overrides + AFK settings
    MANAGE_MEMBERS(Group.GENERAL),         // assign roles to members + manage invites
    KICK_MEMBERS(Group.GENERAL),           // remove a member from the server (permanent)
    BAN_MEMBERS(Group.GENERAL),            // ban a member (can no longer log in / connect)
    MANAGE_NICKNAMES(Group.GENERAL),       // rename other members
    CHANGE_NICKNAME(Group.GENERAL),        // rename yourself
    VIEW_AUDIT_LOG(Group.GENERAL),         // future: audit log
    MANAGE_EMOJIS(Group.GENERAL),          // upload/remove custom emojis and sticker packs

    // --- Text ---
    SEND_MESSAGES(Group.TEXT),
    MANAGE_MESSAGES(Group.TEXT),           // edit/delete others' messages, pin, close discussions
    ATTACH_FILES(Group.TEXT),
    RESERVED_EMBED_LINKS(Group.TEXT, true),          // retired 2026-10-02 (never enforced; link previews
                                                     // are client-side) — bit 14 reserved, see class javadoc
    READ_MESSAGE_HISTORY(Group.TEXT),
    MENTION_EVERYONE(Group.TEXT),
    RESERVED_USE_EXTERNAL_EMOJIS(Group.TEXT, true),  // retired 2026-10-02 (no external catalogs exist on a
                                                     // single-tenant instance) — bit 17 reserved
    ADD_REACTIONS(Group.TEXT),             // put/remove reactions (built-in and custom emoji)

    // --- Voice ---
    CONNECT(Group.VOICE),                  // join a voice room
    SPEAK(Group.VOICE),                    // future: enforce voice publish (suppression on join)
    MUTE_MEMBERS(Group.VOICE),             // server-mute others in a voice room
    DEAFEN_MEMBERS(Group.VOICE),           // server-deafen others
    MOVE_MEMBERS(Group.VOICE),             // kick / move a member out of a voice room
    USE_VAD(Group.VOICE),                  // future: only meaningful once push-to-talk exists
    PRIORITY_SPEAKER(Group.VOICE),         // future
    STREAM(Group.VOICE),                   // future: enforce screen/camera share
    REQUEST_TO_SPEAK(Group.VOICE),         // future: stage channels

    // --- Channel access (labeled GENERAL for the catalog; bit position at the very END —
    // an in-group insert would remap every later group's persisted bits) ---
    VIEW_CHANNEL(Group.GENERAL),           // see a channel at all: private channels deny it for
                                           // @everyone (hidden) or deny read/connect (locked, visible);
                                           // AuthorizationService treats it as the master gate for
                                           // every other channel permission

    // --- Storage (appended at the very END — the bit-stability rule) ---
    MANAGE_STORAGE(Group.GENERAL),         // edit the storage-GC policy and trigger cleanups
                                           // (/api/server/storage — the "Server" settings screen)

    // --- Private-message restriction (appended at the very END — the bit-stability rule) ---
    RESTRICT_PRIVATE_MESSAGES(Group.GENERAL);  // the ONE subtractive permission: a role carrying this
                                               // bit takes direct messages AWAY from its holders —
                                               // they can neither send nor receive (a 1:1 with a
                                               // restricted member freezes for BOTH sides; existing
                                               // conversations stay readable). Checked via RAW role
                                               // masks in AuthorizationService.dmRestricted — NEVER
                                               // via effectiveBase/hasServer: the OR-of-roles model
                                               // would both leak the bit into every names list and
                                               // short-circuit OWNER/ADMINISTRATOR into ALL (an
                                               // admin would appear "restricted" without any role).
                                               // @everyone's copy of the bit is ignored by design.

    public enum Group { GENERAL, TEXT, VOICE }

    private final Group group;
    private final boolean reserved;

    /** This permission's stable bit. Set from {@code ordinal()} during construction (the JVM assigns
     *  the ordinal before the enum body runs, so the field initializer sees the final value). */
    public final long bit = 1L << ordinal();

    Permission(Group group) {
        this(group, false);
    }

    Permission(Group group, boolean reserved) {
        this.group = group;
        this.reserved = reserved;
    }

    public Group getGroup() {
        return group;
    }

    /** True for retired placeholder constants: they keep the bit position occupied but must never
     *  surface in the catalog or on the wire (see the class javadoc retirement protocol). */
    public boolean isReserved() {
        return reserved;
    }
}

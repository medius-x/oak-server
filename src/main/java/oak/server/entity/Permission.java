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
 * they light up as the matching feature (reactions, emojis, audit log …) lands.
 */
public enum Permission {

    // --- General (server-wide authority) ---
    ADMINISTRATOR(Group.GENERAL),          // bypasses every permission check (except hierarchy vs OWNER)
    MANAGE_SERVER(Group.GENERAL),          // edit instance-level identity (name/icon via /api/server) —
                                           // enforced on PATCH /api/server and its avatar endpoints
    MANAGE_ROLES(Group.GENERAL),           // create/edit/delete/reorder roles
    MANAGE_CHANNELS(Group.GENERAL),        // create/edit/delete channels
    MANAGE_MEMBERS(Group.GENERAL),         // assign roles to members
    KICK_MEMBERS(Group.GENERAL),           // disconnect a member from the server
    BAN_MEMBERS(Group.GENERAL),            // ban a member (can no longer log in / connect)
    MANAGE_NICKNAMES(Group.GENERAL),       // rename other members
    CHANGE_NICKNAME(Group.GENERAL),        // rename yourself
    VIEW_AUDIT_LOG(Group.GENERAL),         // future: audit log
    MANAGE_EMOJIS(Group.GENERAL),          // future: custom emojis

    // --- Text ---
    SEND_MESSAGES(Group.TEXT),
    MANAGE_MESSAGES(Group.TEXT),           // edit/delete others' messages
    ATTACH_FILES(Group.TEXT),
    EMBED_LINKS(Group.TEXT),
    READ_MESSAGE_HISTORY(Group.TEXT),
    MENTION_EVERYONE(Group.TEXT),
    USE_EXTERNAL_EMOJIS(Group.TEXT),       // future
    ADD_REACTIONS(Group.TEXT),             // future

    // --- Voice ---
    CONNECT(Group.VOICE),                  // join a voice room
    SPEAK(Group.VOICE),
    MUTE_MEMBERS(Group.VOICE),             // server-mute others in a voice room
    DEAFEN_MEMBERS(Group.VOICE),           // server-deafen others
    MOVE_MEMBERS(Group.VOICE),             // kick / move a member out of a voice room
    USE_VAD(Group.VOICE),                  // voice activity (vs push-to-talk)
    PRIORITY_SPEAKER(Group.VOICE),
    STREAM(Group.VOICE),                   // screen share
    REQUEST_TO_SPEAK(Group.VOICE),         // future: stage channels

    // --- Channel access (labeled GENERAL for the catalog; bit position at the very END —
    // an in-group insert would remap every later group's persisted bits) ---
    VIEW_CHANNEL(Group.GENERAL),           // see a channel at all: private channels deny it for
                                           // @everyone (hidden) or deny read/connect (locked, visible);
                                           // AuthorizationService treats it as the master gate for
                                           // every other channel permission

    // --- Storage (appended at the very END — the bit-stability rule) ---
    MANAGE_STORAGE(Group.GENERAL);         // edit the storage-GC policy and trigger cleanups
                                           // (/api/server/storage — the "Server" settings screen)

    public enum Group { GENERAL, TEXT, VOICE }

    private final Group group;

    /** This permission's stable bit. Set from {@code ordinal()} during construction (the JVM assigns
     *  the ordinal before the enum body runs, so the field initializer sees the final value). */
    public final long bit = 1L << ordinal();

    Permission(Group group) {
        this.group = group;
    }

    public Group getGroup() {
        return group;
    }
}

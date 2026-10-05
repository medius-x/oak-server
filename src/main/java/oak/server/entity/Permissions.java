package oak.server.entity;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stateless helpers for the {@link Permission} bitmask. Permissions are stored and resolved as a
 * single {@code long} (OR of {@link Permission#bit}); this converts between the mask and the
 * name-based view the REST API speaks ({@code Set<String>}), so the client never does bit math.
 */
public final class Permissions {

    /** Every permission granted (used for OWNER / ADMINISTRATOR short-circuit). */
    public static final long ALL = -1L;
    public static final long NONE = 0L;

    private Permissions() {
    }

    public static boolean has(long mask, Permission permission) {
        return (mask & permission.bit) != 0;
    }

    /** ORs the given masks together. */
    public static long union(long first, long... additional) {
        long combined = first;
        for (long extra : additional) {
            combined |= extra;
        }
        return combined;
    }

    /** All permissions set in {@code mask}, as an {@link EnumSet}. */
    public static EnumSet<Permission> fromMask(long mask) {
        EnumSet<Permission> set = EnumSet.noneOf(Permission.class);
        for (Permission permission : Permission.values()) {
            if (has(mask, permission)) {
                set.add(permission);
            }
        }
        return set;
    }

    /** The names of every permission set in {@code mask}, sorted for stable output. Reserved
     *  (retired) bits are skipped — they never surface on the wire; a stale persisted bit
     *  (a pre-V16 database) would otherwise leak a "RESERVED_…" name into role/permission lists. */
    public static Set<String> names(long mask) {
        Set<String> names = new TreeSet<>();
        for (Permission permission : fromMask(mask)) {
            if (!permission.isReserved()) {
                names.add(permission.name());
            }
        }
        return names;
    }

    /** Wire name list for a MEMBER's effective grants: {@link #names} minus the subtractive
     *  {@link Permission#RESTRICT_PRIVATE_MESSAGES} — it is not a grant (an ADMINISTRATOR's mask is
     *  ALL and would carry it without any restricting role), so it must not reach client
     *  {@code can()}-style gating. Role round-trips (the editor's checked set) still use {@link #names}. */
    public static Set<String> grantNames(long mask) {
        Set<String> names = names(mask);
        names.remove(Permission.RESTRICT_PRIVATE_MESSAGES.name());
        return names;
    }

    /** Builds a mask from permission names; unknown names are ignored (forward-compat with newer clients). */
    public static long fromNames(Set<String> names) {
        long mask = NONE;
        if (names == null) {
            return mask;
        }
        for (String name : names) {
            try {
                mask |= Permission.valueOf(name).bit;
            } catch (IllegalArgumentException unknownName) {
                // ignore — client may reference a permission this server build doesn't know
            }
        }
        return mask;
    }

    public static long toMask(Collection<Permission> permissions) {
        long mask = NONE;
        if (permissions == null) {
            return mask;
        }
        for (Permission permission : permissions) {
            mask |= permission.bit;
        }
        return mask;
    }
}

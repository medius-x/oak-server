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

    /** The names of every permission set in {@code mask}, sorted for stable output. */
    public static Set<String> names(long mask) {
        Set<String> names = new TreeSet<>();
        for (Permission permission : fromMask(mask)) {
            names.add(permission.name());
        }
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

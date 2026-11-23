package oak.server.service.igdb;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure naming logic of the IGDB match ladder: normalizing an exe/folder name into a
 * searchable title, sanitizing anything that is about to be embedded in an Apicalypse
 * query body, and deriving the match-cache keys. Static and side-effect-free — the
 * unit-test home of the ladder's heuristics.
 *
 * <p><b>Why normalize at all</b> (live-probed against IGDB, 2026-11-18): the fuzzy
 * {@code search} does not forgive glued words — {@code search "cyberpunk2077"} and
 * {@code search "eldenring"} answer empty, while {@code search "cyberpunk 2077"} finds
 * the game. Real exe names are glued ("Cyberpunk2077.exe"), so the ladder must split
 * CamelCase humps and digit groups before searching.
 *
 * <p><b>Why sanitize</b>: the search string is interpolated into the Apicalypse BODY
 * — a hostile client could otherwise append {@code "; limit 500; where ..."} to the
 * query (or break out of the quoted string). Every character with syntactic meaning
 * in Apicalypse is stripped before embedding; Steam app ids additionally only ever
 * pass as {@code ^[0-9]{1,12}$}.
 */
public final class IgdbNames {

    /** Match-cache key prefix of the exact Steam-appid route. */
    public static final String STEAM_KEY_PREFIX = "steam:";

    /** Match-cache key prefix of the normalized-name search route. */
    static final String NAME_KEY_PREFIX = "name:";

    /** Bound on a normalized title / sanitized query (match_key column is 256). */
    private static final int MAX_TITLE_LENGTH = 100;

    /** Split points inside a glued name: lower→Upper humps (EldenRing), a ≥3-letter
     *  word running into digits (Cyberpunk2077 → "… 2077", but the acronym bg3 stays
     *  glued), and a ≥3-digit number running into letters (2077Night). Short pieces
     *  (cs2, dx11, kc2) are left alone — splitting them only adds search noise. */
    private static final Pattern GLUED_BOUNDARY = Pattern.compile(
            "(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z]{3})(?=[0-9])|(?<=[0-9]{3})(?=[A-Za-z])");

    /** Path separators — Windows and POSIX — collapsed into spaces. */
    private static final Pattern PATH_OR_GLUE = Pattern.compile("[/_\\-.]+");

    /** Every character with syntactic meaning inside an Apicalypse body. */
    private static final Pattern APICALYPSE_META = Pattern.compile("[\"'`;\\\\\n\r\t]");

    /** Multi-space collapse, applied after every split. */
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private IgdbNames() {
    }

    /** The match-cache key of the Steam-appid route. */
    public static String steamKey(String appId) {
        return STEAM_KEY_PREFIX + appId;
    }

    /** The match-cache key of the normalized-name route. */
    public static String nameKey(String normalizedTitle) {
        return NAME_KEY_PREFIX + normalizedTitle;
    }

    /** Only plain digits pass — anything else is a 400 before IGDB is ever touched. */
    public static boolean isValidSteamAppId(String raw) {
        return raw != null && raw.matches("[0-9]{1,12}");
    }

    /**
     * Turns an exe name or a game-folder name into the searchable title: basename →
     * strip {@code .exe} → path/glue separators to spaces → split glued humps and
     * digit groups → lowercase → collapse. Null when nothing searchable remains.
     * Examples: {@code Cyberpunk2077.exe} → {@code "cyberpunk 2077"},
     * {@code ELDEN RING} → {@code "elden ring"}, {@code bg3_dx11} → {@code "bg3 dx11"}.
     */
    public static String normalizeTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.trim();
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1); // a whole path still normalizes
        }
        // the title becomes an Apicalypse BODY fragment — strip its metacharacters
        // HERE so every downstream use (search query, cache key, equality check)
        // operates on the already-sanitized form
        name = APICALYPSE_META.matcher(name).replaceAll("");
        if (name.length() >= 4 && name.regionMatches(true, name.length() - 4, ".exe", 0, 4)) {
            name = name.substring(0, name.length() - 4);
        }
        String title = SPACES.matcher(GLUED_BOUNDARY.matcher(PATH_OR_GLUE.matcher(name)
                        .replaceAll(" "))
                        .replaceAll(" "))
                .replaceAll(" ")
                .toLowerCase()
                .trim();
        if (title.isEmpty()) {
            return null;
        }
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    /**
     * Strips every Apicalypse metacharacter from a string about to be embedded in a
     * query body — the injection gate for the {@code search "..."} interpolation.
     * Null when nothing remains.
     */
    public static String sanitizeSearchQuery(String raw) {
        if (raw == null) {
            return null;
        }
        String sanitized = SPACES.matcher(APICALYPSE_META.matcher(raw).replaceAll(""))
                .replaceAll(" ").trim();
        if (sanitized.isEmpty()) {
            return null;
        }
        return sanitized.length() > MAX_TITLE_LENGTH
                ? sanitized.substring(0, MAX_TITLE_LENGTH) : sanitized;
    }

    /**
     * The search candidates for one lookup, best first: the game FOLDER name (Steam
     * folders are usually human-titled, "ELDEN RING") before the exe name ("eldenring"
     * — un-splittable when all-lowercase). Either input may be absent. Already
     * normalized, sanitized, deduped.
     */
    public static List<String> searchCandidates(String exeName, String folderName) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String raw : java.util.Arrays.asList(folderName, exeName)) { // nulls are legal here
            String normalized = normalizeTitle(raw);
            if (normalized != null) {
                candidates.add(normalized);
            }
        }
        return new ArrayList<>(candidates);
    }
}

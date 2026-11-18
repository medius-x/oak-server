package oak.server.service.igdb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The naming heuristics of the match ladder. The normalize expectations encode what
 * the live probe against IGDB (2026-11-18) established: glued words search empty
 * ("cyberpunk2077" → nothing) while the split form finds the game — so CamelCase
 * humps and digit groups MUST split; and the sanitize expectations encode the
 * Apicalypse injection gate — nothing that could close the {@code search "..."}
 * string or start a new statement may survive.
 */
class IgdbNamesTest {

    @Test
    void normalizeSplitsGluedCamelCaseAndDigitGroups() {
        assertEquals("cyberpunk 2077", IgdbNames.normalizeTitle("Cyberpunk2077.exe"));
        assertEquals("elden ring", IgdbNames.normalizeTitle("EldenRing.exe"));
        assertEquals("counter strike 2", IgdbNames.normalizeTitle("CounterStrike2.exe"));
        assertEquals("2077 night", IgdbNames.normalizeTitle("2077Night.exe"));
    }

    @Test
    void normalizeTakesTheBasenameAndDropsSeparators() {
        assertEquals("eldenring",
                IgdbNames.normalizeTitle("C:\\Games\\ELDEN RING\\Game\\eldenring.exe"));
        assertEquals("counter strike global offensive",
                IgdbNames.normalizeTitle("Counter-Strike Global Offensive"));
        // short glued pieces (bg3, dx11) stay glued — splitting them is search noise;
        // "Win64" is a ≥3-letter word before digits, so it does split — harmless noise
        assertEquals("bg3 dx11", IgdbNames.normalizeTitle("bg3_dx11.exe"));
        assertEquals("sifu win 64 shipping", IgdbNames.normalizeTitle("Sifu-Win64-Shipping.exe"));
        assertEquals("cs2", IgdbNames.normalizeTitle("cs2.exe"));
    }

    @Test
    void normalizeRejectsEmptyAndExtensionOnlyNames() {
        assertNull(IgdbNames.normalizeTitle(null));
        assertNull(IgdbNames.normalizeTitle("   "));
        assertNull(IgdbNames.normalizeTitle(".exe"));
        assertNull(IgdbNames.normalizeTitle("----.exe"));
    }

    @Test
    void sanitizeStripsEveryApicalypseMetacharacter() {
        // a breakout attempt: close the search string, start a new statement
        assertEquals("weird limit 500", IgdbNames.sanitizeSearchQuery("weird\"; limit 500;"));
        assertEquals("still fine", IgdbNames.sanitizeSearchQuery("still 'fine'"));
        assertNull(IgdbNames.sanitizeSearchQuery("\"'`;\\\n\r\t"));
        assertNull(IgdbNames.sanitizeSearchQuery(null));
    }

    @Test
    void steamAppIdsArePlainDigitsOnly() {
        assertTrue(IgdbNames.isValidSteamAppId("730"));
        assertTrue(IgdbNames.isValidSteamAppId("1091500"));
        assertFalse(IgdbNames.isValidSteamAppId("730; drop"));
        assertFalse(IgdbNames.isValidSteamAppId("730\"; where id = 1"));
        assertFalse(IgdbNames.isValidSteamAppId(""));
        assertFalse(IgdbNames.isValidSteamAppId(null));
        assertFalse(IgdbNames.isValidSteamAppId("1234567890123")); // > 12 digits
    }

    @Test
    void searchCandidatesPreferTheFolderNameAndDeduplicate() {
        // the Steam folder is human-titled ("Cyberpunk 2077") — it lands FIRST and the
        // exe's glued form dedupes into it once both normalize to the same title
        assertEquals(List.of("cyberpunk 2077"),
                IgdbNames.searchCandidates("Cyberpunk2077.exe", "Cyberpunk 2077"));
        // only the folder is present
        assertEquals(List.of("elden ring"), IgdbNames.searchCandidates(null, "ELDEN RING"));
        // both differ: folder first, exe second
        assertEquals(List.of("cyberpunk 2077", "cyberpunk 2077 launcher"),
                IgdbNames.searchCandidates("Cyberpunk2077Launcher.exe", "Cyberpunk 2077"));
        // nothing usable at all
        assertEquals(List.of(), IgdbNames.searchCandidates("  ", ".exe"));
    }
}

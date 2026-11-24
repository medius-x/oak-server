package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.GameCard;
import oak.server.dto.GameConfirmRequest;
import oak.server.dto.GameLookupResponse;
import oak.server.dto.MyGameInfo;
import oak.server.service.AuthorizationService;
import oak.server.service.igdb.GameSessionService;
import oak.server.service.igdb.IgdbException;
import oak.server.service.igdb.IgdbNames;
import oak.server.service.igdb.IgdbGameService;
import oak.server.service.igdb.IgdbLookupRateLimiter;
import oak.server.service.igdb.IgdbNotConfiguredException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The IGDB game-database endpoints behind the {@code igdb-games} capability. All
 * three share {@link IgdbLookupRateLimiter} (the instance's 4-rps IGDB budget is a
 * shared resource) and are open to any authenticated member — a game card is public
 * catalogue data, nothing instance-private. Upstream failures translate cleanly:
 * 503 = the integration is not configured (no Twitch credentials), 502 = IGDB itself
 * failed (token endpoint or API), so the client can tell the two apart.
 */
@RestController
@RequestMapping("/api/games")
@RequiredArgsConstructor
public class GameController {

    /** Longest accepted exe/folder name — real ones are far shorter; the rest is spray. */
    private static final int MAX_NAME_LENGTH = 200;

    private final IgdbGameService gameService;
    private final GameSessionService gameSessions;
    private final IgdbLookupRateLimiter rateLimiter;
    private final AuthorizationService auth;
    private final oak.server.service.sgdb.SgdbIconService sgdbIcons;
    private final oak.server.service.igdb.IgdbCoverService covers;
    private final oak.server.repository.ServerProfileRepository serverProfiles;

    /**
     * Runs the match ladder: the exact Steam-appid route first (the client reads it
     * from {@code steamapps/appmanifest_*.acf}), then the fuzzy search over the
     * normalized folder/exe names. Answer: a confident {@code game}, or top
     * {@code candidates} for the user to pin via confirm, or both-empty "not found".
     */
    @GetMapping("/lookup")
    public GameLookupResponse lookup(@AuthenticationPrincipal UserDetails principal,
                                     @RequestParam(required = false) String appId,
                                     @RequestParam(required = false) String exeName,
                                     @RequestParam(required = false) String folderName) {
        auth.requireUser(principal.getUsername()); // banned/removed members look up nothing
        if (!rateLimiter.tryAcquire(principal.getUsername())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many game lookups");
        }
        appId = trimToNull(appId);
        if (appId != null && !IgdbNames.isValidSteamAppId(appId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed Steam app id");
        }
        exeName = requireShort(trimToNull(exeName), "exeName");
        folderName = requireShort(trimToNull(folderName), "folderName");
        if (appId == null && exeName == null && folderName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to look up");
        }
        try {
            GameLookupResponse answer = gameService.lookup(appId, exeName, folderName);
            // The icon lane rides along (background, once per game): a Steam-resolved
            // card plus the request's appid is everything SGDB needs; the answer
            // itself never waits on the second upstream.
            if (answer.game() != null && appId != null) {
                sgdbIcons.queueForAppId(answer.game().igdbId(), appId);
            }
            return answer;
        } catch (IgdbNotConfiguredException notConfigured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IGDB is not configured");
        } catch (IgdbException upstream) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, upstream.getMessage());
        }
    }

    /** One cached card by IGDB id — the roster-view dogleg for games other members play. */
    @GetMapping("/{igdbId}")
    public GameCard card(@AuthenticationPrincipal UserDetails principal,
                         @PathVariable long igdbId) {
        auth.requireUser(principal.getUsername());
        if (!rateLimiter.tryAcquire(principal.getUsername())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many game requests");
        }
        try {
            GameCard card = gameService.cardOf(igdbId);
            if (card == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown game");
            }
            if (card.iconUrl() == null && sgdbIcons.active()) {
                // The backfill path: the client re-fetches cards whose icon is
                // still absent — each fetch re-queues (the lane dedupes; a parked
                // breaker or a miss simply keeps the IGDB square).
                sgdbIcons.queueBackfill(card.igdbId());
            }
            return card;
        } catch (IgdbNotConfiguredException notConfigured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IGDB is not configured");
        } catch (IgdbException upstream) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, upstream.getMessage());
        }
    }

    /**
     * The game's cached SteamGridDB icon BYTES (V23): clients render this, not
     * the CDN — the CDN host is unreachable from some client machines (DPI or
     * a local proxy; the broken-picture smoke 2026-11-23), while the server's
     * own reach was verified by the resolution itself. 404 = no icon, OR the
     * icon layer is PAUSED — a cached value is deliberately NOT served while
     * paused (user semantics, 2026-11-23) — the client falls back to the
     * cover square. The bytes are immutable per game, so the response is
     * fully cacheable.
     */
    @GetMapping("/{igdbId}/icon")
    public org.springframework.http.ResponseEntity<byte[]> icon(@AuthenticationPrincipal UserDetails principal,
                                                                @PathVariable long igdbId) {
        auth.requireUser(principal.getUsername());
        byte[] bytes = sgdbIcons.active() ? sgdbIcons.cachedIconBytes(igdbId) : null;
        if (bytes == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No icon for this game");
        }
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, sgdbIcons.cachedIconMime(igdbId))
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, max-age=604800, immutable")
                .body(bytes);
    }

    /**
     * A client's OFFER of the icon bytes it fetched itself (2026-11-24, the
     * burst-throttled transit round): the card's {@code pendingIconUrl} told
     * it what the server's lane is still fighting for, and a healthy member
     * machine's network often delivers first. The bytes are UNTRUSTED: only
     * PNG/ICO magic survives sniffing, the cache cap stands, and the store is
     * the atomic first-wins UPDATE — this offer, our own lane's attempt or
     * another client's, exactly one wins; losers answer 200 as idempotent
     * no-ops (the icon is THERE either way). 201 = this offer became the
     * icon, 200 = one already stood. Reading is capped at cap+1 bytes, so a
     * lying Content-Length cannot balloon memory.
     */
    @PostMapping("/{igdbId}/icon")
    public org.springframework.http.ResponseEntity<Void> offerIcon(@AuthenticationPrincipal UserDetails principal,
                                                                   @PathVariable long igdbId,
                                                                   jakarta.servlet.http.HttpServletRequest request)
            throws java.io.IOException {
        auth.requireUser(principal.getUsername());
        if (!rateLimiter.tryAcquire(principal.getUsername())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many game requests");
        }
        if (!sgdbIcons.active()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The icon layer is off or paused");
        }
        long declaredBytes = request.getContentLengthLong();
        if (declaredBytes > oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Icon over the cache cap");
        }
        byte[] bytes = request.getInputStream().readNBytes(oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES + 1);
        if (bytes.length > oak.server.entity.IgdbGame.SGDB_ICON_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Icon over the cache cap");
        }
        if (oak.server.service.sgdb.SgdbIconService.sniffIconMime(bytes) == null) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PNG and ICO icons are accepted");
        }
        boolean stored = sgdbIcons.acceptClientIcon(igdbId, bytes);
        return stored
                ? org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).build()
                : org.springframework.http.ResponseEntity.ok().build();
    }

    /**
     * The game's IGDB cover THUMBNAIL bytes (V23): the panel's fallback picture,
     * proxied through the server for the same unreachable-CDN reason. Serves
     * only while the game integration is ACTIVE — pausing IGDB mutes the whole
     * game stack (the user's both-toggles rule). Fetch-on-miss: the very first
     * request for a game pays one CDN round trip, everything after serves the
     * cached row.
     */
    @GetMapping("/{igdbId}/cover")
    public org.springframework.http.ResponseEntity<byte[]> cover(@AuthenticationPrincipal UserDetails principal,
                                                                 @PathVariable long igdbId) {
        auth.requireUser(principal.getUsername());
        if (!rateLimiter.tryAcquire(principal.getUsername())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many game requests");
        }
        if (!oak.server.service.igdb.IgdbAuthService.isIntegrationActive(
                serverProfiles.singleton())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IGDB is not configured");
        }
        byte[] bytes = covers.coverThumb(igdbId);
        if (bytes == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No cover for this game");
        }
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "image/jpeg")
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "private, max-age=604800, immutable")
                .body(bytes);
    }

    /**
     * The caller's play history aggregate (2026-11-19): every game with a
     * session, card + summed playtime + last played, newest first (capped 30).
     * Own-DB read only — no IGDB round, no rate limit; an unconfigured or
     * paused integration simply has no sessions and answers empty.
     */
    @GetMapping("/my")
    public List<MyGameInfo> myGames(@AuthenticationPrincipal UserDetails principal) {
        auth.requireUser(principal.getUsername());
        return gameSessions.myGames(principal.getUsername(), 30);
    }

    /** Pins the user's pick from an ambiguous lookup into the shared match cache. */
    @PostMapping("/confirm")
    public void confirm(@AuthenticationPrincipal UserDetails principal,
                        @RequestBody @Valid GameConfirmRequest request) {
        auth.requireUser(principal.getUsername());
        if (!rateLimiter.tryAcquire(principal.getUsername())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many game requests");
        }
        String appId = trimToNull(request.appId());
        if (appId != null && !IgdbNames.isValidSteamAppId(appId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed Steam app id");
        }
        String exeName = requireShort(trimToNull(request.exeName()), "exeName");
        String folderName = requireShort(trimToNull(request.folderName()), "folderName");
        if (appId == null && exeName == null && folderName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to pin");
        }
        try {
            gameService.confirm(appId, exeName, folderName, request.igdbId());
        } catch (IgdbNotConfiguredException notConfigured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IGDB is not configured");
        } catch (IgdbException upstream) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, upstream.getMessage());
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, unknown.getMessage());
        }
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String requireShort(String name, String field) {
        if (name != null && name.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is too long");
        }
        return name;
    }
}

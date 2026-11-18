package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.GameCard;
import oak.server.dto.GameConfirmRequest;
import oak.server.dto.GameLookupResponse;
import oak.server.service.AuthorizationService;
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
    private final IgdbLookupRateLimiter rateLimiter;
    private final AuthorizationService auth;

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
            return gameService.lookup(appId, exeName, folderName);
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
            return card;
        } catch (IgdbNotConfiguredException notConfigured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IGDB is not configured");
        } catch (IgdbException upstream) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, upstream.getMessage());
        }
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

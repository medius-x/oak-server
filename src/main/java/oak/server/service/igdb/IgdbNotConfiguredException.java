package oak.server.service.igdb;

/**
 * The IGDB integration is switched off: either Twitch credential is missing on the
 * server profile. The REST layer maps it to 503 so the client can tell "not
 * configured" from "IGDB is down" (502) and hide the auto-detect UI accordingly.
 */
public class IgdbNotConfiguredException extends RuntimeException {
}

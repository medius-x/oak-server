package oak.server.controller;

import oak.server.entity.ServerProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit coverage for {@link InviteController#buildLink(ServerProfile, String, String)} — the
 * scheme/port matrix introduced with the TLS entry point (provisioning round B): the scheme
 * comes from the advertised {@code connectionScheme} (null = http), and the port is printed
 * only when advertised AND non-default for that scheme (80/443 omitted). The Host-header
 * fallback keeps whatever port it already carries.
 */
class InviteLinkBuildTest {

    private static String link(String host, Integer port, String scheme, String hostHeader) {
        ServerProfile profile = new ServerProfile("OAK Server");
        profile.setConnectionHost(host);
        profile.setConnectionPort(port);
        profile.setConnectionScheme(scheme);
        return InviteController.buildLink(profile, hostHeader, "SECRET");
    }

    @Test
    void historicalDefaultIsHttpWithExplicitNonDefaultPort() {
        // The dev topology: http + the historical 8080 (80 is the scheme default, 8080 is not).
        assertEquals("http://localhost:8080/#invite=SECRET",
                link("localhost", 8080, null, null));
    }

    @Test
    void httpsOmitsTheDefaultPort() {
        assertEquals("https://203.0.113.10/#invite=SECRET",
                link("203.0.113.10", 443, "https", null));
        // no port advertised at all — same shape
        assertEquals("https://203.0.113.10/#invite=SECRET",
                link("203.0.113.10", null, "https", null));
    }

    @Test
    void httpOmitsPort80ButKeepsOthers() {
        assertEquals("http://oak.lan/#invite=SECRET", link("oak.lan", 80, "http", null));
        assertEquals("http://oak.lan:8080/#invite=SECRET", link("oak.lan", 8080, "http", null));
    }

    @Test
    void nonDefaultHttpsPortPrints() {
        assertEquals("https://oak.example.com:8443/#invite=SECRET",
                link("oak.example.com", 8443, "https", null));
    }

    @Test
    void hostHeaderFallbackCarriesItsOwnPortUntouched() {
        // Nothing advertised: the scheme still applies, the Host header (with or without a
        // port) is used verbatim and the advertised-port logic never appends.
        assertEquals("https://10.0.0.5:443/#invite=SECRET",
                link(null, null, "https", "10.0.0.5:443"));
        assertEquals("http://192.168.0.20:8080/#invite=SECRET",
                link(null, 9999, null, "192.168.0.20:8080"));
    }
}

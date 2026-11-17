package oak.server.websocket;

import oak.server.dto.RosterAfkReport;
import oak.server.dto.RosterStatusReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The roster STATUS report endpoints: the username is stamped from the authenticated
 * principal (never from the body — a report can only describe its own sender), the
 * session id keys the tracker entry, and junk bodies (null body/principal, an unknown
 * status) are silent no-ops. The legacy boolean wire bridges onto the same model.
 */
@ExtendWith(MockitoExtension.class)
class RosterStatusHandlerTest {

    @Mock
    private RosterStatusTracker tracker;

    @InjectMocks
    private RosterStatusHandler handler;

    private static Principal principalOf(String username) {
        return () -> username;
    }

    @Test
    void statusReportIsAppliedWithThePrincipalAndSessionId() {
        handler.reportStatus(new RosterStatusReport("dnd", null), principalOf("alice"), "s1");
        verify(tracker).setStatus("s1", "alice", "dnd", false);

        handler.reportStatus(new RosterStatusReport("online", true), principalOf("alice"), "s1");
        verify(tracker).setStatus("s1", "alice", "online", true);
    }

    @Test
    void unknownOrNullStatusesAreSilentNoOps() {
        handler.reportStatus(new RosterStatusReport("hidden", null), principalOf("alice"), "s1");
        handler.reportStatus(new RosterStatusReport(null, null), principalOf("alice"), "s1");
        handler.reportStatus(null, principalOf("alice"), "s1");
        handler.reportStatus(new RosterStatusReport("away", null), null, "s1");
        verifyNoInteractions(tracker);
    }

    @Test
    void theLegacyAfkWireBridgesOntoTheModel() {
        handler.reportAfk(new RosterAfkReport(true), principalOf("alice"), "s1");
        handler.reportAfk(new RosterAfkReport(null), principalOf("alice"), "s1");
        handler.reportAfk(null, principalOf("alice"), "s1");
        verify(tracker).setAway("s1", "alice", true);
    }
}

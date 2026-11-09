package oak.server.websocket;

import oak.server.dto.RosterAfkReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The roster AFK report endpoint: the username is stamped from the authenticated
 * principal (never from the body — a report can only describe its own sender), the
 * session id keys the tracker entry, and junk bodies are silent no-ops.
 */
@ExtendWith(MockitoExtension.class)
class RosterAfkHandlerTest {

    @Mock
    private RosterAfkTracker tracker;

    @InjectMocks
    private RosterAfkHandler handler;

    private static Principal principalOf(String username) {
        return () -> username;
    }

    @Test
    void reportIsAppliedWithThePrincipalAndSessionId() {
        handler.report(new RosterAfkReport(true), principalOf("alice"), "s1");
        verify(tracker).setAway("s1", "alice", true);
    }

    @Test
    void nullBodyIsNullAfkAndNullPrincipalAreSilentNoOps() {
        handler.report(null, principalOf("alice"), "s1");
        handler.report(new RosterAfkReport(null), principalOf("alice"), "s1");
        handler.report(new RosterAfkReport(true), null, "s1");
        verifyNoInteractions(tracker);
    }
}

package oak.server;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The IPv6 liveness probe's reachable/unreachable verdict (2026-11-23, the
 * broken-v6 hosting post-mortem): the unreachable half is deterministic —
 * the documentation prefix {@code 2001:db8::/32} is never routed, so the
 * probe must read it as dead within its timeout. The reachable half needs a
 * live dual-stack network and is covered by real boots instead.
 */
class AppIpProbeTest {

    @Test
    void anUnroutableV6AddressReadsAsUnreachable() throws Exception {
        InetAddress documentationV6 = InetAddress.getByAddress(new byte[]{
                0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        assertFalse(App.ipv6Reachable(documentationV6, 500));
    }
}

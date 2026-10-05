package oak.server.service;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M6 brute-force gate for {@code POST /api/auth/login}: rolling windows in the
 * {@link DirectContactGate} idiom (per-key deques of failure timestamps, no dependencies) —
 * per-ACCOUNT 20 failures / 10 min and per-IP 60 failures / 10 min, either exceeded → the
 * login answers 429 before any credential check. A SUCCESSFUL login clears that account's
 * window (an honest user who finally types their password right is not punished for the
 * attempts before it); the IP window only ever expires by time (a shared NAT that breeds
 * failures stays throttled). The uniform login error is preserved — the 429 text says
 * nothing about whether the account exists.
 *
 * <p><b>Recording is separate from checking:</b> a blocked attempt adds nothing, so a
 * hammering attacker's window stops growing the moment the cap hits and fully drains 10
 * minutes after the LAST RECORDED failure — the sustained rate settles at exactly the cap.
 * Only wrong passwords count: {@code BadCredentialsException} covers both an unknown user
 * and a wrong password, so the two are indistinguishable here too.
 *
 * <p><b>Memory:</b> both maps are keyed by attacker-chosen input (arbitrary usernames, IPs).
 * Beyond {@link #MAP_CAP} distinct keys the maps are first pruned of fully-expired windows
 * and, should a spray still flood the cap, cleared wholesale — an availability-over-precision
 * trade-off fit for a single-tenant small instance (the alternative, unbounded growth, is
 * a self-inflicted DoS).
 */
@Component
public class LoginRateLimiter {

    /** Public for the cross-package integration test (the MockMvc brute-force walk). */
    public static final long WINDOW_MILLIS = 10 * 60 * 1000L;
    public static final int ACCOUNT_FAILURE_CAP = 20;
    public static final int ADDRESS_FAILURE_CAP = 60;
    /** Distinct-key ceiling per map before the stale prune / wholesale clear kicks in. */
    private static final int MAP_CAP = 4096;

    private final Map<String, Deque<Long>> accountFailures = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> addressFailures = new ConcurrentHashMap<>();

    /** Whether a login attempt for this (username, address) pair must be refused right now. */
    public boolean isBlocked(String username, String remoteAddress) {
        return countFailures(accountFailures, username) >= ACCOUNT_FAILURE_CAP
                || countFailures(addressFailures, remoteAddress) >= ADDRESS_FAILURE_CAP;
    }

    /** Records a failed attempt on both windows (wrong password / unknown user). */
    public void onFailure(String username, String remoteAddress) {
        record(accountFailures, username);
        record(addressFailures, remoteAddress);
    }

    /** Clears the account's failure window — the IP window is time-drained only. */
    public void onSuccess(String username) {
        accountFailures.remove(username);
    }

    /** Failures currently inside the window for one key (trims the expired head first). */
    private static int countFailures(Map<String, Deque<Long>> windows, String key) {
        if (key == null) {
            return 0;
        }
        Deque<Long> window = windows.get(key);
        if (window == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                window.pollFirst();
            }
            return window.size();
        }
    }

    /** Appends a failure timestamp, trimming the expired head (DirectContactGate's tryAcquire shape). */
    private static void record(Map<String, Deque<Long>> windows, String key) {
        if (key == null) {
            return;
        }
        if (windows.size() >= MAP_CAP) {
            pruneStale(windows);
        }
        Deque<Long> window = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (window) {
            long now = System.currentTimeMillis();
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                window.pollFirst();
            }
            window.addLast(now);
        }
    }

    /** Drops fully-expired windows; a spray that still floods the cap resets the map wholesale. */
    private static void pruneStale(Map<String, Deque<Long>> windows) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Deque<Long>>> entries = windows.entrySet().iterator();
        while (entries.hasNext()) {
            Deque<Long> window = entries.next().getValue();
            synchronized (window) {
                while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
                    window.pollFirst();
                }
                if (window.isEmpty()) {
                    entries.remove();
                }
            }
        }
        if (windows.size() >= MAP_CAP) {
            windows.clear();
        }
    }
}

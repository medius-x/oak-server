package oak.server.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/server/health} — the live status of oak-server's four backing integrations
 * (postgres, minio, janus, coturn), each with two facets: a SERVICE probe that oak-server runs
 * against the integration itself (is it actually usable from here) and the docker CONTAINER
 * state (running/exited + the image's own healthcheck verdict when it defines one). Wire
 * contract: append-only — new fields land at the end, mirrored in space-client.
 */
public record ServerHealthResponse(Instant checkedAt, List<Entry> services) {

    public record Entry(String name, ServiceCheck service, ContainerCheck container) {}

    /**
     * {@code UP} (probe answered), {@code DOWN} (probe failed — detail says why),
     * {@code DISABLED} (the integration is switched off by configuration, e.g. the test
     * profile) or {@code TIMEOUT} (the probe outlived its budget).
     */
    public record ServiceCheck(String status, Integer latencyMillis, String detail) {}

    /**
     * The docker state uppercased ({@code RUNNING}, {@code EXITED}, …) when a container
     * matched, {@code ABSENT} when nothing matches the configured name, or {@code UNKNOWN}
     * when docker itself is unavailable/disabled. {@code health} carries the image
     * HEALTHCHECK verdict ({@code HEALTHY}/{@code UNHEALTHY}/{@code STARTING}/{@code NONE});
     * janus and coturn images define no healthcheck, {@code NONE} there is normal.
     */
    public record ContainerCheck(String status, String health, String detail) {}
}

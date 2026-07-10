package oak.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO configuration bound from {@code oak.minio.*} (see application-dev.yml).
 *
 * <p>{@code publicEndpoint} is the address clients use and the one baked into presigned URLs;
 * {@code endpoint} is the server's internal address (kept separate for non-dev topologies).
 * {@code enabled=false} disables MinIO entirely — used by the test profile, which has no MinIO
 * and runs against a noop signer.
 */
@ConfigurationProperties(prefix = "oak.minio")
public record MinioProperties(
        String endpoint,
        String publicEndpoint,
        String accessKey,
        String secretKey,
        String bucket,
        boolean enabled
) {
}

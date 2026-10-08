package oak.server.config;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Two faces of MinIO access (the TLS entry point split, provisioning round B): the server
 * OPERATES on the internal {@code endpoint} (bucket checks, deletes, listings — inside the
 * compose network), while presigned URLs must carry the {@code publicEndpoint} clients can
 * actually reach — the SDK signs against the endpoint of the client instance, hence two
 * beans. Both point at the same storage in the dev topology. Active only when
 * {@code oak.minio.enabled=true}; the test profile disables MinIO and a noop signer
 * ({@code NoopAttachmentUrlSigner}) stands in instead.
 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    /** Bucket lifecycle, deletes, listings — the server's own dials (internal address). */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "oak.minio", name = "enabled", havingValue = "true")
    MinioClient minioOperationsClient(MinioProperties properties) {
        return client(properties.endpoint() != null && !properties.endpoint().isBlank()
                ? properties.endpoint()
                : properties.publicEndpoint(), properties);
    }

    /** Presigned PUT/GET URLs — signed for the address CLIENTS use. */
    @Bean(name = "minioPresignClient")
    @ConditionalOnProperty(prefix = "oak.minio", name = "enabled", havingValue = "true")
    MinioClient minioPresignClient(MinioProperties properties) {
        return client(properties.publicEndpoint(), properties);
    }

    private static MinioClient client(String endpoint, MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(endpoint)
                // Pin the region explicitly: without it the SDK PROBES the endpoint over
                // the network on the first presign (region discovery) — fine from the
                // dev host where localhost:9000 answers, but from inside the compose
                // network the PUBLIC endpoint (https://host:9443) is unreachable, and
                // every signature died "Failed to connect" → 500 on any DTO with an
                // avatar URL (live smoke 2026-10-08). With a region set, presigning is
                // a purely local HMAC and never touches the network.
                .region("us-east-1")
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }
}

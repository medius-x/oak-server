package oak.server.config;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the MinIO {@link MinioClient} on the {@code publicEndpoint} so presigned URLs carry an
 * address clients can actually reach. Active only when {@code oak.minio.enabled=true}; the test
 * profile disables MinIO and a noop signer ({@code NoopAttachmentUrlSigner}) stands in instead.
 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    @Bean
    @ConditionalOnProperty(prefix = "oak.minio", name = "enabled", havingValue = "true")
    MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.publicEndpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }
}

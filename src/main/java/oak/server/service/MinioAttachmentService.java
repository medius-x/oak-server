package oak.server.service;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import oak.server.config.MinioProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MinIO-backed signer. Presigned PUT/GET URLs are signed by the PUBLIC-address client
 * ({@code minioPresignClient}); every server-side operation (bucket check at startup,
 * deletes, orphan listings) runs on the INTERNAL-address client — behind the Caddy entry
 * point these are different hosts. Active only when {@code oak.minio.enabled=true}.
 *
 * <p>The constructor injects TWO beans of the same type and states the presign one
 * with an EXPLICIT {@code @Qualifier}: parameter-name-based matching did NOT survive
 * to the compiled jar (live smoke 2026-10-08 — every presigned URL left on the
 * internal {@code http://minio:9000} address because the {@code @Primary} operations
 * client won BOTH constructor slots), and a qualifier does not depend on compiler
 * flags at all.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "oak.minio", name = "enabled", havingValue = "true")
public class MinioAttachmentService implements AttachmentUrlSigner, AttachmentObjectStore {

    private static final Duration PUT_EXPIRY = Duration.ofMinutes(5);
    private static final Duration GET_EXPIRY = Duration.ofHours(1);

    private final MinioClient minioOperationsClient;
    private final MinioClient minioPresignClient;
    private final MinioProperties properties;

    /** Written by hand (not @RequiredArgsConstructor) for the explicit @Qualifier —
     *  see the class javadoc for the matching failure this prevents. */
    public MinioAttachmentService(MinioClient minioOperationsClient,
                                  @Qualifier("minioPresignClient") MinioClient minioPresignClient,
                                  MinioProperties properties) {
        this.minioOperationsClient = minioOperationsClient;
        this.minioPresignClient = minioPresignClient;
        this.properties = properties;
    }

    @PostConstruct
    void ensureBucket() {
        String bucket = properties.bucket();
        try {
            if (!minioOperationsClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                minioOperationsClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[MinIO] Created bucket '{}'", bucket);
            }
        } catch (Exception minioError) {
            throw new IllegalStateException(
                    "Could not verify/create MinIO bucket '" + bucket + "': " + minioError.getMessage(), minioError);
        }
    }

    @Override
    public String newKey(String fileName) {
        return key("attachments", fileName);
    }

    @Override
    public String newAvatarKey(String fileName) {
        return key("avatars", fileName);
    }

    @Override
    public String newOriginalKey(String fileName) {
        return key("avatars/originals", fileName);
    }

    @Override
    public String newServerAvatarKey(String fileName) {
        return key("server-avatars", fileName);
    }

    @Override
    public String newEmojiKey(String fileName) {
        return key("custom-emojis", fileName);
    }

    @Override
    public String newStickerKey(String fileName) {
        return key("stickers", fileName);
    }

    private static String key(String prefix, String fileName) {
        return prefix + "/" + UUID.randomUUID() + "/" + safeName(fileName);
    }

    @Override
    public String presignedPutUrl(String key) {
        return sign(Method.PUT, key, PUT_EXPIRY);
    }

    @Override
    public String presignedGetUrl(String key) {
        return sign(Method.GET, key, GET_EXPIRY);
    }

    @Override
    public void delete(String key) {
        try {
            minioOperationsClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(key)
                    .build());
        } catch (Exception minioError) {
            // Best-effort: a leftover orphan object is harmless, but log it so it's visible.
            log.warn("[MinIO] Could not delete object '{}': {}", key, minioError.getMessage());
        }
    }

    @Override
    public List<StoredObject> listAttachmentObjects() {
        // The recursive listing under the attachments/ prefix only — avatars have their own
        // lifecycle (replaced/removed with the profile) and are never swept.
        return listByPrefix("attachments/");
    }

    @Override
    public List<StoredObject> listCustomEmojiObjects() {
        // The emoji catalog's own listing: reclaimed rows are deleted by EmojiController, the
        // only orphan here is an abandoned upload slot (night sweep checks the emoji table).
        return listByPrefix("custom-emojis/");
    }

    @Override
    public List<StoredObject> listStickerObjects() {
        // The sticker catalog's listing — the same abandoned-slot reclaim story as emojis.
        return listByPrefix("stickers/");
    }

    private List<StoredObject> listByPrefix(String prefix) {
        List<StoredObject> objects = new ArrayList<>();
        Iterable<Result<Item>> listing = minioOperationsClient.listObjects(ListObjectsArgs.builder()
                .bucket(properties.bucket())
                .prefix(prefix)
                .recursive(true)
                .build());
        for (Result<Item> entry : listing) {
            try {
                Item item = entry.get();
                objects.add(new StoredObject(item.objectName(), item.size(),
                        Instant.ofEpochMilli(item.lastModified().toInstant().toEpochMilli())));
            } catch (Exception minioError) {
                log.warn("[MinIO] Listing skipped one object: {}", minioError.getMessage());
            }
        }
        return objects;
    }

    private String sign(Method method, String key, Duration expiry) {
        try {
            return minioPresignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                            .method(method)
                            .bucket(properties.bucket())
                            .object(key)
                            .expiry((int) expiry.getSeconds())
                            .build());
        } catch (Exception minioError) {
            throw new IllegalStateException("Could not sign MinIO URL for '" + key + "': " + minioError.getMessage(), minioError);
        }
    }

    private static String safeName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "file";
        }
        String name = Paths.get(fileName).getFileName().toString(); // strip any path the client sent
        return name.isBlank() ? "file" : name;
    }
}

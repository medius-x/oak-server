package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The instance's own identity: a single row (always {@code id = 1}) holding the server-stored
 * {@code name} and avatar keys. There is no guild entity — the instance <i>is</i> the server — so
 * this is a one-row singleton edited via {@code /api/server} (gated by {@code MANAGE_SERVER}) and
 * read by every client on connect ({@code GET /api/server}).
 *
 * <p>Like {@link User}, the avatar lives in MinIO behind presigned URLs; {@code avatarKey} is the
 * stable object key and {@code avatarOriginalKey} the pre-crop original kept for re-cropping.
 *
 * <p>No {@code @Data}/@ToString/@EqualsAndHashCode — JPA entity convention (matches {@link User}).
 */
@Entity
@Table(name = "server_profile")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** MinIO object key of the server icon, or null when none is set (presigned GET is signed per request). */
    @Column(name = "avatar_key", length = 512)
    private String avatarKey;

    /** MinIO object key of the original (pre-crop) icon image — lets an editor re-crop without
     *  re-selecting a file. Loaded lazily via {@code GET /api/server/avatar/original}. */
    @Column(name = "avatar_original_key", length = 512)
    private String avatarOriginalKey;

    /** Host the instance ADVERTISES for client connections — what a member types into "Add server".
     *  Storage for the future remote-provisioning flow (the setup client will push it here); seeded
     *  with a manual/test value on fresh rows, null on rows created before the field existed. */
    @Column(name = "connection_host", length = 255)
    private String connectionHost;

    /** TCP port the advertised host is reached on (e.g. 8080 — REST and WS share it). Null = not set. */
    @Column(name = "connection_port")
    private Integer connectionPort;

    public ServerProfile(String name) {
        this.name = name;
    }
}

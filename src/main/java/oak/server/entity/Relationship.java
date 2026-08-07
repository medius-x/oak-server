package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A directed edge between two users: a pending friend request, an established friendship, or a block.
 * The {@code (source_id, target_id)} unique constraint makes a request/friendship/block idempotent per
 * ordered pair — a friendship occupies both {@code (a,b)} and {@code (b,a)}, while a pending request
 * or a block occupies a single directed row.
 *
 * <p>No {@code @Data}/@ToString/@EqualsAndHashCode here (lazy associations, unstable id-based hashing),
 * matching the other JPA entities.
 */
@Entity
@Table(name = "relationships",
        uniqueConstraints = @UniqueConstraint(columnNames = {"source_id", "target_id"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Relationship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private User source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id", nullable = false)
    private User target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RelationshipType type;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Relationship(User source, User target, RelationshipType type) {
        this.source = source;
        this.target = target;
        this.type = type;
    }
}

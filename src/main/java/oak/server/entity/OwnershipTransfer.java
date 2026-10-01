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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A server-ownership transfer offer (the friend-request machinery applied to the OWNER
 * role): the owner offers, the recipient accepts or declines, and only the accept moves
 * the role. Single-tenant, so at most one {@link OwnershipTransferStatus#PENDING} row
 * exists at a time — a new offer cancels the previous one. The previous owner keeps all
 * their other roles; the transfer back can only be initiated by the NEW owner.
 *
 * <p>No {@code @Data}/@ToString/@EqualsAndHashCode here (lazy associations, unstable
 * id-based hashing), matching the other JPA entities.
 */
@Entity
@Table(name = "ownership_transfer")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnershipTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_user_id", nullable = false)
    private User fromUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_user_id", nullable = false)
    private User toUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OwnershipTransferStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public OwnershipTransfer(User fromUser, User toUser, OwnershipTransferStatus status) {
        this.fromUser = fromUser;
        this.toUser = toUser;
        this.status = status;
    }
}

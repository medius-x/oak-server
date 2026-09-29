package oak.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One user's one reaction on one message — a Discord-style emoji row. The {@code reaction} column
 * stores either a built-in whitelisted slug (e.g. {@code thumbsup}) or a colon-wrapped
 * custom-emoji token (e.g. {@code :cat:}), never the emoji glyph itself: both stay ASCII-safe on
 * the wire (URL paths) and in the database. The value grammar is enforced by {@code MessageService}
 * ({@code REACTION_SLUGS} + the {@code :name:} pattern); catalog EXISTENCE for custom tokens is
 * validated at add time only, so a row may dangle after the emoji is deleted from the catalog
 * (the client degrades the chip to text and the remove path stays open).
 *
 * <p>{@code (message_id, user_id, reaction)} is unique: a user reacts with a given emoji at most
 * once per message (the PUT add is idempotent via this constraint). Cascade is absent — rows are
 * cleaned up explicitly before a message delete (no {@code ON DELETE CASCADE} under
 * {@code ddl-auto=update}).
 */
@Entity
@Table(name = "message_reactions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "user_id", "reaction"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Built-in emoji slug ({@code MessageService.REACTION_SLUGS}) or a colon-wrapped
     *  custom-emoji token ({@code :name:}, name ≤ 32 — hence the 64-char column). Never the glyph. */
    @Column(length = 64, nullable = false)
    private String reaction;

    @CreationTimestamp
    @Column(name = "reacted_at", nullable = false, updatable = false)
    private Instant reactedAt;

    public MessageReaction(Message message, User user, String reaction) {
        this.message = message;
        this.user = user;
        this.reaction = reaction;
    }
}

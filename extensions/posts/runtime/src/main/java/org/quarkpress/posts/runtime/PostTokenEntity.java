package org.quarkpress.posts.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

/**
 * A stored token of a site: a preview session ({@code postId} null) or a lock release token. Only the SHA-256 of the
 * token is kept, as 64 hexadecimal characters.
 */
@Entity
@Table(name = "qp_post_token")
@Filter(name = SiteScopedEntity.FILTER)
public class PostTokenEntity extends SiteScopedEntity {

    /** The kind of a preview session. */
    public static final String PREVIEW_SESSION = "preview_session";

    /** The kind of a lock release token. */
    public static final String LOCK_RELEASE = "lock_release";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "kind", nullable = false, length = 20)
    public String kind;

    @Column(name = "post_id")
    public Long postId;

    @Column(name = "user_id", nullable = false)
    public long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    public String tokenHash;

    @Column(name = "lock_value", length = 40)
    public String lockValue;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}

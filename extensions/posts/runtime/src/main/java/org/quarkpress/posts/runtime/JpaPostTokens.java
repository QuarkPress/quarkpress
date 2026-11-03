package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.quarkpress.core.PostId;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.LockReleaseTokens;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PreviewSessions;
import org.quarkpress.posts.PreviewTokens;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Memberships;

/**
 * Preview tokens, preview sessions and lock release tokens. Preview tokens are HMAC-SHA256 values over the post, the
 * user and the current tick with the site's secret, an option created under the site's write lock (the site's post lock) the first time a
 * token is issued; nothing else is stored for them. Sessions and release tokens are rows of {@code qp_post_token}
 * holding the SHA-256 of a random token; every lookup names the site and the kind, and a release token also its post
 * and user, so a token of one kind is never found as another. Expired rows of the site are deleted when a session is
 * opened or a release token issued: the ids of at most 500 expired rows are selected, then deleted by id, the same two
 * statements on both databases. Refusals come before any write, so they do not mark the caller's transaction.
 */
@ApplicationScoped
public class JpaPostTokens implements PreviewTokens, PreviewSessions, LockReleaseTokens {

    /** The bytes of a session or release token, and of the site's preview secret. */
    private static final int TOKEN_BYTES = 32;

    /** The bytes of a preview token: the HMAC truncated. */
    private static final int PREVIEW_BYTES = 20;

    /** A preview token's length as base64url text without padding. */
    private static final int PREVIEW_LENGTH = 27;

    /** Expired rows deleted per purge. */
    private static final int PURGE_BATCH = 500;

    /** The longest lock value stored with a release token. */
    private static final int LOCK_VALUE_LENGTH = 40;

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    @Inject
    SiteContext site;

    @Inject
    PostRepository posts;

    @Inject
    PostLocks siteLocks;

    @Inject
    Acting acting;

    @Inject
    PostCapabilityFacts facts;

    @Inject
    Capabilities capabilities;

    @Inject
    Memberships memberships;

    @Inject
    Options options;

    @Inject
    OptionRepository optionRows;

    @Inject
    QuarkPressClock clock;

    @Inject
    PostsConfig config;

    private final SecureRandom random = new SecureRandom();

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public String issue(PostId post, UserId user) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(user, "user");
        SiteId siteId = site.siteId();
        requireCaller(user);
        requireEditable(siteId, post);
        return preview(secretOrCreate(siteId), siteId, post, user, tick(clock.now()));
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public boolean verify(PostId post, String token, UserId viewer) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(viewer, "viewer");
        if (token.length() != PREVIEW_LENGTH) {
            return false;
        }
        SiteId siteId = site.siteId();
        Optional<PostEntity> row = posts.findPost(siteId, post);
        if (row.isEmpty()
                || !capabilities.userCan(
                        viewer,
                        siteId,
                        "edit_post",
                        Optional.of(facts.of(row.get().toSnapshot())))) {
            return false;
        }
        Optional<byte[]> secret = secret();
        if (secret.isEmpty()) {
            return false;
        }
        long tick = tick(clock.now());
        byte[] given = token.getBytes(StandardCharsets.UTF_8);
        boolean current = MessageDigest.isEqual(given, ascii(preview(secret.get(), siteId, post, viewer, tick)));
        boolean previous = MessageDigest.isEqual(given, ascii(preview(secret.get(), siteId, post, viewer, tick - 1)));
        return current | previous;
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Session open(UserId user) {
        Objects.requireNonNull(user, "user");
        SiteId siteId = site.siteId();
        requireCaller(user);
        if (!memberships.isMember(siteId, user) && !capabilities.isSuperAdmin(user)) {
            throw new ForbiddenException("the caller is not a member of site " + siteId.value());
        }
        Instant now = clock.now();
        purgeExpired(siteId, now);
        Duration lifetime = config.previewLifetime();
        String token = randomToken();
        store(PostTokenEntity.PREVIEW_SESSION, null, user, token, null, now, lifetime);
        return new Session(token, lifetime);
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public Optional<UserId> user(String token) {
        Objects.requireNonNull(token, "token");
        SiteId siteId = site.siteId();
        return session(siteId, token)
                .filter(row -> row.expiresAt.isAfter(clock.now()))
                .map(row -> UserId.of(row.userId));
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public void close(String token) {
        Objects.requireNonNull(token, "token");
        SiteId siteId = site.siteId();
        session(siteId, token).ifPresent(row -> deleteRow(siteId, row));
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public String issue(PostId post, UserId user, String lockValue) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(lockValue, "lockValue");
        if (lockValue.length() > LOCK_VALUE_LENGTH) {
            throw new IllegalArgumentException("a lock value has at most " + LOCK_VALUE_LENGTH + " characters");
        }
        SiteId siteId = site.siteId();
        requireCaller(user);
        requireEditable(siteId, post);
        Instant now = clock.now();
        purgeExpired(siteId, now);
        String token = randomToken();
        store(PostTokenEntity.LOCK_RELEASE, post, user, token, lockValue, now, config.previewLifetime());
        return token;
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public boolean consume(PostId post, UserId user, String token, String lockValue) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(lockValue, "lockValue");
        SiteId siteId = site.siteId();
        requireCaller(user);
        Optional<PostTokenEntity> found = entityManager()
                .createQuery(
                        "select t from PostTokenEntity t where t.siteId = :site and t.kind = :kind"
                                + " and t.postId = :post and t.userId = :user and t.tokenHash = :hash",
                        PostTokenEntity.class)
                .setParameter("site", TenantIds.param(siteId))
                .setParameter("kind", PostTokenEntity.LOCK_RELEASE)
                .setParameter("post", post.value())
                .setParameter("user", user.value())
                .setParameter("hash", hash(token))
                .getResultStream()
                .findFirst();
        if (found.isEmpty()) {
            return false;
        }
        PostTokenEntity row = found.get();
        boolean consumed = deleteRow(siteId, row);
        return consumed && row.expiresAt.isAfter(clock.now()) && lockValue.equals(row.lockValue);
    }

    /**
     * Deletes one row by id with a statement of its own and answers whether this transaction deleted it: of two
     * transactions consuming the same token, the statement of the second one affects no row, so only one answers
     * true, whatever the order their flushes would have had.
     */
    private boolean deleteRow(SiteId siteId, PostTokenEntity row) {
        EntityManager entityManager = entityManager();
        entityManager.flush();
        int deleted = entityManager
                .createQuery("delete from PostTokenEntity t where t.siteId = :site and t.id = :id")
                .setParameter("site", TenantIds.param(siteId))
                .setParameter("id", row.id)
                .executeUpdate();
        entityManager.detach(row);
        return deleted == 1;
    }

    /** The caller must be {@code user}: the system, an anonymous caller and any other user are refused. */
    private void requireCaller(UserId user) {
        if (!acting.user().filter(user::equals).isPresent()) {
            throw new ForbiddenException("the caller is not user " + user.value());
        }
    }

    /** {@code edit_post} on the post as found, without locks. */
    private void requireEditable(SiteId siteId, PostId post) {
        PostEntity row = posts.findPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        acting.require(siteId, "edit_post", Optional.of(facts.of(row.toSnapshot())));
    }

    private Optional<PostTokenEntity> session(SiteId siteId, String token) {
        return entityManager()
                .createQuery(
                        "select t from PostTokenEntity t where t.siteId = :site and t.kind = :kind"
                                + " and t.tokenHash = :hash",
                        PostTokenEntity.class)
                .setParameter("site", TenantIds.param(siteId))
                .setParameter("kind", PostTokenEntity.PREVIEW_SESSION)
                .setParameter("hash", hash(token))
                .getResultStream()
                .findFirst();
    }

    private void store(
            String kind, PostId post, UserId user, String token, String lockValue, Instant now, Duration lifetime) {
        PostTokenEntity row = new PostTokenEntity();
        row.kind = kind;
        row.postId = post == null ? null : post.value();
        row.userId = user.value();
        row.tokenHash = hash(token);
        row.lockValue = lockValue;
        row.createdAt = now;
        row.expiresAt = now.plus(lifetime);
        entityManager().persist(row);
        entityManager().flush();
    }

    /** Deletes at most {@link #PURGE_BATCH} expired rows of the site, the oldest ids first. */
    private void purgeExpired(SiteId siteId, Instant now) {
        EntityManager entityManager = entityManager();
        List<Long> ids = entityManager
                .createQuery(
                        "select t.id from PostTokenEntity t where t.siteId = :site and t.expiresAt < :now"
                                + " order by t.id",
                        Long.class)
                .setParameter("site", TenantIds.param(siteId))
                .setParameter("now", now)
                .setMaxResults(PURGE_BATCH)
                .getResultList();
        if (ids.isEmpty()) {
            return;
        }
        entityManager
                .createQuery("delete from PostTokenEntity t where t.siteId = :site and t.id in :ids")
                .setParameter("site", TenantIds.param(siteId))
                .setParameter("ids", ids)
                .executeUpdate();
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return URL.encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The current tick: the time divided by half the preview lifetime (at least one second). */
    private long tick(Instant now) {
        long half = Math.max(1, config.previewLifetime().toSeconds() / 2);
        return Math.floorDiv(now.getEpochSecond(), half);
    }

    private static String preview(byte[] secret, SiteId siteId, PostId post, UserId user, long tick) {
        String message = "preview|" + siteId.value() + "|" + post.value() + "|" + user.value() + "|" + tick;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] full = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[PREVIEW_BYTES];
            System.arraycopy(full, 0, truncated, 0, PREVIEW_BYTES);
            return URL.encodeToString(truncated);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** The site's secret as stored; empty when the site has none. */
    private Optional<byte[]> secret() {
        return site.options().get(SECRET_OPTION).map(JpaPostTokens::secretBytes);
    }

    /**
     * The site's secret, created under the site's write lock (the site's post lock) when the site has none: an insert-if-absent, then the
     * stored value read under the option's lock when another transaction inserted it first.
     */
    private byte[] secretOrCreate(SiteId siteId) {
        Optional<byte[]> stored = secret();
        if (stored.isPresent()) {
            return stored.get();
        }
        siteLocks.lock(siteId);
        byte[] secret = new byte[TOKEN_BYTES];
        random.nextBytes(secret);
        String encoded = Base64.getEncoder().encodeToString(secret);
        if (options.add(SECRET_OPTION, TextNode.valueOf(encoded), false)) {
            return secret;
        }
        return optionRows
                .findOptionForUpdate(siteId, SECRET_OPTION)
                .map(row -> secretBytes(row.value))
                .orElseThrow(() -> new IllegalStateException(
                        "the option " + SECRET_OPTION + " of site " + siteId.value() + " vanished while it was read"));
    }

    /**
     * The stored secret: a base64 text of exactly 32 bytes. Anything else (a hand-edited option) refuses every
     * preview with {@link IllegalStateException} until the option is deleted and created again, rather than
     * becoming a weak key.
     */
    private static byte[] secretBytes(JsonNode value) {
        byte[] decoded = null;
        if (value.isTextual()) {
            try {
                decoded = Base64.getDecoder().decode(value.textValue());
            } catch (IllegalArgumentException notBase64) {
                decoded = null;
            }
        }
        if (decoded == null || decoded.length != TOKEN_BYTES) {
            throw new IllegalStateException("the option " + SECRET_OPTION + " is not a base64 text of " + TOKEN_BYTES
                    + " bytes: delete it so that the next preview creates a new secret");
        }
        return decoded;
    }

    private EntityManager entityManager() {
        return posts.getEntityManager();
    }
}

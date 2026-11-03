package org.quarkpress.posts;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.UserId;

/**
 * The preview sessions of a site: a session names the user who opens a preview page, as WordPress's login cookie
 * does, and lives for the preview lifetime ({@code quarkpress.posts.preview-lifetime}). A session token is 32 random
 * bytes as base64url text without padding (43 characters); only its SHA-256 is stored, so a token cannot be read back.
 * Sessions belong to the site they were opened on and are never found through another site, nor through any other
 * kind of token lookup.
 */
public interface PreviewSessions {

    /** An open session: its token and how long it lives from now. */
    record Session(String token, Duration lifetime) {
        public Session {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(lifetime, "lifetime");
        }
    }

    /**
     * Opens a session for {@code user}. The caller must be {@code user} and a member of the site (or a super admin);
     * otherwise {@code ForbiddenException}. Expired sessions and release tokens of the site are purged first, in a
     * bounded batch.
     */
    Session open(UserId user);

    /** The user of an unexpired session of this site; empty for anything else. */
    Optional<UserId> user(String token);

    /** Revokes a session of this site; an unknown or already closed token is ignored. */
    void close(String token);

    /**
     * Revokes every session of {@code user} on this site, expired ones included. The caller must be {@code user};
     * otherwise {@code ForbiddenException}. A user without sessions is left as is.
     */
    void closeAll(UserId user);
}

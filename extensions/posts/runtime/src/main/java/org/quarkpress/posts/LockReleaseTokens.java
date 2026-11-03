package org.quarkpress.posts;

import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;

/**
 * One-time tokens that let an editor release its post lock when it leaves the post, as WordPress's edit screen holds
 * an unlock nonce. A token is 32 random bytes as base64url text without padding (43 characters), bound to the post,
 * the user and the lock value it was issued for, and lives for the preview lifetime
 * ({@code quarkpress.posts.preview-lifetime}). Only its SHA-256 is stored. A token is never found through another
 * site, another post, another user or any other kind of token lookup.
 */
public interface LockReleaseTokens {

    /**
     * A new token for the caller, who must be {@code user} (otherwise {@code ForbiddenException}) and needs
     * {@code edit_post} on the post; a missing post is refused with {@link PostException} {@code invalid_post}, a lock
     * value longer than a stored lock with {@link IllegalArgumentException}. Expired sessions and release tokens of the
     * site are purged first, in a bounded batch, and the user's earlier release tokens for this post are deleted:
     * one token per user and post, the one of the newest lock value (the only value that can still release).
     */
    String issue(PostId post, UserId user, String lockValue);

    /**
     * True once for an unexpired token of this site bound to {@code post}, {@code user} and {@code lockValue}; the
     * caller must be {@code user} (otherwise {@code ForbiddenException}, nothing consumed). The token is consumed
     * either way, so a token presented with another lock value answers false and is gone; of two concurrent calls
     * with the same token only one answers true.
     */
    boolean consume(PostId post, UserId user, String token, String lockValue);
}

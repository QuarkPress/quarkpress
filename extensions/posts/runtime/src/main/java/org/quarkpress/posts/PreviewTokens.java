package org.quarkpress.posts;

import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;

/**
 * The preview token of a post for a user: QuarkPress's counterpart of the nonce WordPress puts into a preview link.
 * Tokens are computed, never stored: a token is the base64url text without padding (27 characters) of the first 20
 * bytes of HMAC-SHA256 over {@code "preview|<site id>|<post id>|<user id>|<tick>"} with the site's secret, where the
 * tick is the current time divided by half the preview lifetime ({@code quarkpress.posts.preview-lifetime}, 12 hours
 * by default, so a tick of 6 hours). The same token is answered for a whole tick, so the editor's repeated requests
 * and its open preview tabs agree, and a token stays valid for the tick after the one it was issued in, so a link is
 * good for at least the lifetime and at most twice as long, as WordPress's nonces are.
 *
 * <p>The site's secret is 32 random bytes, created under the site's write lock the first time a token is issued and
 * kept in the site's options as {@code quarkpress_preview_secret}: not a registered setting, never exposed through
 * REST. Deleting the option revokes every preview link of the site; the next issue creates a new secret.
 */
public interface PreviewTokens {

    /** The option holding the site's secret, as base64 text. */
    String SECRET_OPTION = "quarkpress_preview_secret";

    /**
     * The token of {@code post} for {@code user} in the current tick. The caller must be {@code user} (otherwise
     * {@code ForbiddenException}) and needs {@code edit_post} on the post; a missing post is refused with
     * {@link PostException} {@code invalid_post}. Nothing is stored but the site's secret, when it does not exist yet.
     */
    String issue(PostId post, UserId user);

    /**
     * Whether {@code token} is the token of {@code post} for {@code viewer} in the current or the previous tick, and
     * {@code viewer} has {@code edit_post} on the post now; compared in constant time. False for a missing post, a
     * site without secret (none is created), a token of another post, user or tick, or a token of any other shape.
     */
    boolean verify(PostId post, String token, UserId viewer);
}

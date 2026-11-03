package org.quarkpress.posts;

import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;

/**
 * A registered key's auth callback, WordPress's {@code auth_post_meta_{key}} filter: whether the key may be written
 * by {@code user} on {@code post} for {@code capability} ({@code edit_post_meta}, {@code add_post_meta} or
 * {@code delete_post_meta}). {@code allowedByDefault} is the answer without a callback: true for a key that is not
 * protected. {@code edit_post} on the post is required besides, whatever the callback answers.
 */
@FunctionalInterface
public interface MetaAuth {

    boolean allowed(boolean allowedByDefault, String key, PostId post, Optional<UserId> user, String capability);
}

package org.quarkpress.posts;

import io.quarkus.security.identity.SecurityIdentity;
import java.util.Objects;
import org.quarkpress.core.PostId;

/**
 * What a {@link MetaSanitizer} is told: the post, its type, the key and the caller's identity (null when nobody is
 * authenticated, as outside a request).
 */
public record MetaContext(PostId post, String type, String key, SecurityIdentity identity) {

    public MetaContext {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
    }
}

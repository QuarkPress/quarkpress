package org.quarkpress.posts;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;

/**
 * What a slug is computed for: the site, the post (empty while it is being inserted), its type, status and parent,
 * and the text the slug started from.
 */
public record SlugContext(
        SiteId site, Optional<PostId> post, String type, String status, Optional<PostId> parent, String original) {

    public SlugContext {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(original, "original");
    }
}

package org.quarkpress.posts;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;

/**
 * What a slug is computed for: the site, the post (empty while it is being inserted), its type, status and parent,
 * and the text the slug started from. {@code original} depends on the step: a {@link SlugSanitizer} gets the raw text
 * it sanitizes (the given slug, or the title when none was given), a {@link PostSlugFilter} gets the sanitized slug as
 * it was before it was made unique. {@code parent} depends on the step too: on an update, a {@link SlugSanitizer} sees
 * the requested parent, before the parent loop check, and a {@link PostSlugFilter} sees the parent after it.
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

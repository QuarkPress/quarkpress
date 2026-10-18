package org.quarkpress.core;

import java.time.Instant;
import java.util.Objects;

/**
 * A site's flags or public value changed (WordPress's {@code archive_blog}, {@code make_spam_blog},
 * {@code make_delete_blog}, {@code mature_blog}, {@code update_blog_public} and their opposites). Fired after the
 * {@link SiteUpdated} of the same change; compare {@code before} and {@code after} to see which flag moved.
 */
public record SiteFlagsChanged(SiteId site, Instant at, SiteInfo before, SiteInfo after) implements SiteEvent {

    public SiteFlagsChanged {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        SiteEvents.requireSame(site, before);
        SiteEvents.requireSame(site, after);
    }
}

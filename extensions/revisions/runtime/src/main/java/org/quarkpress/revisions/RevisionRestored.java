package org.quarkpress.revisions;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/**
 * A revision was restored onto its post (WordPress's {@code wp_restore_post_revision}): fired after the post was saved
 * (and the revision of that save written) and before the revision's meta replaces the post's.
 */
public record RevisionRestored(SiteId site, Instant at, PostId post, RevisionId revision) implements SiteEvent {
    public RevisionRestored {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(revision, "revision");
    }
}

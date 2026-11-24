package org.quarkword.revisions;

import java.time.Instant;
import java.util.Objects;
import org.quarkword.core.SiteEvent;
import org.quarkword.core.SiteId;

/** A revision or a new autosave was written (WordPress's {@code _wp_put_post_revision}). */
public record RevisionCreated(SiteId site, Instant at, RevisionSnapshot revision) implements SiteEvent {
    public RevisionCreated {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(revision, "revision");
    }
}

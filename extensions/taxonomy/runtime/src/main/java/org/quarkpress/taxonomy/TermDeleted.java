package org.quarkpress.taxonomy;

import java.time.Instant;
import java.util.Objects;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;

/** A term was deleted (WordPress's {@code delete_term}), after its posts and children were handed on. */
public record TermDeleted(SiteId site, Instant at, TermSnapshot term) implements SiteEvent {
    public TermDeleted {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(term, "term");
    }
}

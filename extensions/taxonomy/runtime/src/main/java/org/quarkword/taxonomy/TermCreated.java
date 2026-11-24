package org.quarkword.taxonomy;

import java.time.Instant;
import java.util.Objects;
import org.quarkword.core.SiteEvent;
import org.quarkword.core.SiteId;

/** A term was created (WordPress's {@code created_term}). */
public record TermCreated(SiteId site, Instant at, TermSnapshot term) implements SiteEvent {
    public TermCreated {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(term, "term");
    }
}

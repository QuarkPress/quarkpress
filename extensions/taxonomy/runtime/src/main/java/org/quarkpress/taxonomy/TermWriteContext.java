package org.quarkpress.taxonomy;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/** What a {@link TermWriteFilter} knows about the write: the site, the taxonomy, the term before it, the caller. */
public record TermWriteContext(
        SiteId site, String taxonomy, Optional<TermSnapshot> before, Optional<UserId> actor, boolean update) {

    public TermWriteContext {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(taxonomy, "taxonomy");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(actor, "actor");
    }
}

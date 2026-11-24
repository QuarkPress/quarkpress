package org.quarkword.posts;

import java.util.Objects;
import java.util.Optional;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;

/** What a {@link PostQueryFilter} knows: the site and the calling user. */
public record QueryContext(SiteId site, Optional<UserId> actor) {
    public QueryContext {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(actor, "actor");
    }
}

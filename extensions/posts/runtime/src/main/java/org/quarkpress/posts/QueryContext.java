package org.quarkpress.posts;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/** What a {@link PostQueryFilter} knows: the site and the calling user. */
public record QueryContext(SiteId site, Optional<UserId> actor) {
    public QueryContext {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(actor, "actor");
    }
}

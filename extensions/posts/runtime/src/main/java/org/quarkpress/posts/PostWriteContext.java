package org.quarkpress.posts;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/** What a {@link PostWriteFilter} knows about the write: the site, the post before it (updates), the calling user. */
public record PostWriteContext(SiteId site, Optional<PostSnapshot> before, Optional<UserId> actor, boolean update) {

    public PostWriteContext {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(actor, "actor");
    }
}

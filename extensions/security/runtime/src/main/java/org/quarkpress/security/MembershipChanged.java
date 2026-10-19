package org.quarkpress.security;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * A user's roles on a site changed, or the user was added to or removed from it (WordPress's
 * {@code add_user_to_blog}, {@code set_user_role}, {@code remove_user_from_blog}). {@code before} and {@code after}
 * are the user's roles on the site, in order; {@code after} is empty when the membership ended.
 */
public record MembershipChanged(SiteId site, Instant at, UserId user, Set<String> before, Set<String> after)
        implements SiteEvent {

    public MembershipChanged {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(user, "user");
        before = Collections.unmodifiableSet(new LinkedHashSet<>(before));
        after = Collections.unmodifiableSet(new LinkedHashSet<>(after));
    }
}

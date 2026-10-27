package org.quarkpress.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;

/**
 * The scheduled posts of every site, soonest first: what the polling job will publish next. For an overview such as
 * the Dev UI's; publishing goes through the posts module.
 */
public interface ScheduledPosts {

    /** The most posts {@link #next} returns. */
    int MAX_LIMIT = 100;

    /** A scheduled post: its site, its id, its type, its title and when it is due (its GMT date). */
    record Upcoming(SiteId site, PostId post, String type, String title, Instant publishAt) {
        public Upcoming {
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(post, "post");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(publishAt, "publishAt");
        }
    }

    /**
     * The scheduled ({@code future}) posts of every site that have a GMT date, soonest first, then by site id, then by
     * post id, at most {@code limit} of them. Overdue ones are included: their site has scheduling off, or the poll has
     * not run yet. They are read in the network scope, as the system, on a thread of their own, so nothing of the
     * caller's context (scope, identity, transaction) applies. The caller must hold the network capability
     * {@code manage_sites} (network administrators and the system identity do), checked on the caller's thread before
     * anything else.
     *
     * @throws org.quarkpress.core.CapabilityDeniedException when the caller lacks {@code manage_sites}
     * @throws IllegalArgumentException when {@code limit} is not between 1 and {@link #MAX_LIMIT}
     *     ({@code limit must be between 1 and 100: <limit>})
     * @throws IllegalStateException when called inside a transaction, as the scheduling jobs are
     */
    List<Upcoming> next(int limit);
}

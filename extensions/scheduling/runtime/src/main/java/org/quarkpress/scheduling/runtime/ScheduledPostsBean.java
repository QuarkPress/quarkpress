package org.quarkpress.scheduling.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.quarkpress.core.CapabilityCheck;
import org.quarkpress.core.NetworkCapabilities;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.scheduling.ScheduledPosts;
import org.quarkpress.security.SystemIdentity;

/**
 * Reads the next scheduled posts across sites the way the jobs read: detached, in the network scope, as the system,
 * once the caller's own network capability is checked on the caller's thread.
 */
@ApplicationScoped
public class ScheduledPostsBean implements ScheduledPosts {

    @Inject
    CapabilityCheck capabilities;

    @Inject
    NetworkScope network;

    @Inject
    SystemIdentity system;

    @Inject
    DuePosts due;

    @Override
    public List<Upcoming> next(int limit) {
        capabilities.requireNetwork(NetworkCapabilities.MANAGE_SITES);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ": " + limit);
        }
        return Jobs.detached(() -> network.call(() -> system.call(() -> due.nextScheduled(limit))));
    }
}

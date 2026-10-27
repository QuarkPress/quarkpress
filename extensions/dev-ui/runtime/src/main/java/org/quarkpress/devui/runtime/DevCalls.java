package org.quarkpress.devui.runtime;

import jakarta.inject.Inject;
import java.util.concurrent.Callable;
import org.quarkpress.core.SiteDirectory;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.SiteScope;

/**
 * How a Dev UI call reaches the application: on a detached thread of its own, under the Dev UI identity, and for a
 * site's data inside that site's scope.
 */
public class DevCalls {

    @Inject
    SiteScope siteScope;

    @Inject
    SiteDirectory directory;

    @Inject
    DevIdentity identity;

    /** Runs {@code work} on a detached thread under the Dev UI identity, bound to no site. */
    public <T> T asSystem(Callable<T> work) {
        return DevThreads.detached(() -> identity.call(work));
    }

    /**
     * Runs {@code work} on a detached thread in the scope of site {@code siteId}, under the Dev UI identity.
     *
     * @throws IllegalArgumentException when {@code siteId} is not a valid site id, or no site has it; the refusal
     *     comes before any scope opens
     */
    public <T> T onSite(long siteId, Callable<T> work) {
        return DevThreads.detached(() -> {
            SiteId site = SiteId.of(siteId);
            if (directory.find(site).isEmpty()) {
                throw new IllegalArgumentException("no site " + siteId);
            }
            return siteScope.call(site, () -> identity.call(work));
        });
    }
}

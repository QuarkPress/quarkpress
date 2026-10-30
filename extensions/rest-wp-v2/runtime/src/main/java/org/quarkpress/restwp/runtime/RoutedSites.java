package org.quarkpress.restwp.runtime;

import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.multisite.runtime.RoutedSite;

/**
 * Reads the multisite module's decision for a request. Loaded only when a request carries such a decision, so an
 * application without the (optional) multisite module never loads it.
 */
final class RoutedSites {

    private RoutedSites() {}

    /** The site a request is bound to; empty when it is unresolved, network-bound or redirected. */
    static Optional<SiteId> site(Object routed) {
        if (routed instanceof RoutedSite site
                && site.redirect().isEmpty()
                && site.binding().kind() == Binding.Kind.SITE) {
            return Optional.of(site.binding().site());
        }
        return Optional.empty();
    }
}

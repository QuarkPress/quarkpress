package org.quarkpress.multisite.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteResolution;

/**
 * Decides a request's site: asks every resolver once (the built-in ones and the application's, by priority) and keeps
 * the redirect of a redirecting alias when the domain resolver's answer won. A resolver that throws fails the
 * resolution. A raw path with {@code .} or {@code ..} segments is served without a site and no resolver is asked:
 * the router serves the path without those segments, which is not the path a site would be looked up for.
 *
 * <p>The alias redirect is matched to the answer by site only: when an application resolver that runs before the
 * domain resolver (priority above 300) answers the very site the alias belongs to, the request still carries the
 * alias's redirect, although the domain resolver's answer did not win.
 */
@ApplicationScoped
public class HttpResolution {

    private static final Logger LOG = Logger.getLogger(HttpResolution.class);

    @Inject
    SiteResolution resolution;

    public RoutedSite route(SiteRequest request) {
        if (!request.isNone() && SiteAddresses.hasDotSegment(request.path())) {
            LOG.debugf("the request path %s has dot segments; the request is served without a site", request.path());
            return new RoutedSite(Binding.UNRESOLVED, Optional.empty());
        }
        RedirectNote.clear();
        NetworkNote.begin();
        try {
            Optional<SiteId> site = resolution.resolve(request);
            Optional<URI> redirect = RedirectNote.take(site);
            return new RoutedSite(site.map(Binding::site).orElse(Binding.UNRESOLVED), redirect);
        } finally {
            RedirectNote.clear();
            NetworkNote.end();
        }
    }
}

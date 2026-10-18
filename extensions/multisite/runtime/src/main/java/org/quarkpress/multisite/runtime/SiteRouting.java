package org.quarkpress.multisite.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.SiteRequest;

/**
 * The built-in strategies. All share WordPress's lookup (a site's domain plus the longest matching path prefix, with
 * the request path and segment count prepared as WordPress prepares them) and differ in the hosts they accept,
 * relative to the network domain: {@code domain} answers aliases for any host and sites on hosts outside the network
 * domain, {@code subdomain} sites on its subdomains, {@code path} sites on the network domain itself.
 */
@ApplicationScoped
public class SiteRouting {

    @Inject
    MultisiteSettingsView settings;

    @Inject
    SiteLookup lookup;

    public Optional<SiteId> match(ResolverStrategy strategy, SiteRequest request) {
        if (request.isNone()
                || request.host().isEmpty()
                || !settings.resolvers().contains(strategy)) {
            return Optional.empty();
        }
        String host = SiteAddresses.requestHost(request.host());
        // Only values a stored domain or path can have are compared: nothing else can match, and the database would
        // refuse some of them (a NUL character), which must not fail the request.
        List<String> domains = SiteAddresses.candidateDomains(host).stream()
                .filter(SiteAddresses::storableDomain)
                .toList();
        if (domains.isEmpty()) {
            return Optional.empty();
        }
        if (strategy == ResolverStrategy.DOMAIN) {
            Optional<SiteLookup.AliasHit> alias = lookup.alias(domains);
            if (alias.isPresent()) {
                SiteLookup.AliasHit hit = alias.get();
                if (hit.redirectToPrimary()) {
                    String scheme = "https".equals(request.scheme()) ? "https" : "http";
                    RedirectNote.note(hit.site(), SiteInfo.url(scheme, hit.domain(), hit.path()));
                }
                return Optional.of(hit.site());
            }
        }
        Optional<SiteLookup.Address> network = NetworkNote.network(lookup::network);
        if (network.isEmpty()) {
            return Optional.empty();
        }
        HostClass hostClass = SiteAddresses.classify(host, network.get().domain());
        boolean accepted = switch (strategy) {
            case DOMAIN -> hostClass == HostClass.FOREIGN;
            case SUBDOMAIN -> hostClass == HostClass.SUBDOMAIN;
            case PATH -> hostClass == HostClass.NETWORK;
        };
        if (!accepted) {
            return Optional.empty();
        }
        String path = SiteAddresses.lookupPath(request.path());
        int segments = SiteAddresses.lookupSegments(
                host, path, network.get().domain(), network.get().path(), settings.pathSegments());
        List<String> paths = SiteAddresses.candidatePaths(path, segments).stream()
                .filter(SiteAddresses::storablePath)
                .toList();
        return lookup.site(domains, paths);
    }
}

package org.quarkpress.multisite.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Set;

/** How the multisite module finds the site of a request. Read it through {@link MultisiteSettingsView}. */
@ConfigMapping(prefix = "quarkpress.multisite")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface MultisiteConfig {

    /**
     * The built-in strategies that resolve sites: {@code domain} (domain aliases, and sites on domains outside the
     * network domain), {@code subdomain} (sites on subdomains of the network domain) and {@code path} (sites under a
     * path of the network domain).
     */
    @WithDefault("domain,subdomain,path")
    Set<ResolverStrategy> resolvers();

    /**
     * How many path segments are compared with site paths for requests not under a network path other than
     * {@code /} (the count WordPress's {@code site_by_path_segments_count} filter sets; WordPress's own is 1); under such
     * a network path, the network path's segments are added. 1 to 16.
     */
    @WithDefault("1")
    int pathSegments();
}

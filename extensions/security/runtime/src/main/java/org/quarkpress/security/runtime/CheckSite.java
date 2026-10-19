package org.quarkpress.security.runtime;

import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.PresetSite;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.runtime.QuarkPressRuntimeConfig;

/**
 * The site a check made now is for: the request's binding (resolved if needed, except on an event-loop thread, where
 * only an already decided binding counts), else the site an HTTP layer decided for the request, else the main site on
 * a single site and no site in a network. A network binding answers for the main site.
 */
@ApplicationScoped
public class CheckSite {

    @Inject
    PresetSite preset;

    @Inject
    InstallMode mode;

    @Inject
    QuarkPressRuntimeConfig config;

    public Optional<SiteId> current() {
        if (Arc.container().requestContext().isActive()) {
            SiteBinding binding = Arc.container().instance(SiteBinding.class).get();
            Optional<Binding> bound =
                    io.vertx.core.Context.isOnEventLoopThread() ? binding.bound() : Optional.of(binding.current());
            if (bound.isPresent()) {
                return siteOf(bound.get());
            }
        }
        Optional<Binding> decided = preset.binding();
        if (decided.isPresent()) {
            return siteOf(decided.get());
        }
        return mode.multisite() ? Optional.empty() : Optional.of(main());
    }

    public SiteId main() {
        return SiteId.of(config.mainSiteId());
    }

    private Optional<SiteId> siteOf(Binding binding) {
        return switch (binding.kind()) {
            case SITE -> Optional.of(binding.site());
            case NETWORK -> Optional.of(main());
            case UNRESOLVED -> Optional.empty();
        };
    }
}

package org.quarkpress.persistence.runtime;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;

/**
 * Without the multisite module every request belongs to the main site ({@code quarkpress.main-site-id}). Asked last,
 * and answers without touching the database.
 */
@ApplicationScoped
@Priority(Integer.MIN_VALUE)
@IfBuildProperty(name = "quarkpress.multisite.enabled", stringValue = "false", enableIfMissing = true)
public class SingleSiteResolver implements SiteResolver {

    @Inject
    QuarkPressRuntimeConfig config;

    @Override
    public Optional<SiteId> resolve(SiteRequest request) {
        return Optional.of(SiteId.of(config.mainSiteId()));
    }
}

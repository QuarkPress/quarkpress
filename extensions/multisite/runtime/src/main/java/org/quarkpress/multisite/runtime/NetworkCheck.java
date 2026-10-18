package org.quarkpress.multisite.runtime;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.quarkpress.core.SiteDirectory;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.QuarkPressRuntimeConfig;

/**
 * The network's address is the main site's domain and path, so the application does not start without the main site
 * (checked after the migrations ran), nor with a path segment count outside 1 to 16.
 */
@ApplicationScoped
@IfBuildProperty(name = "quarkpress.multisite.enabled", stringValue = "true")
public class NetworkCheck {

    @Inject
    MultisiteSettingsView settings;

    @Inject
    QuarkPressRuntimeConfig persistence;

    @Inject
    SiteDirectory directory;

    @ActivateRequestContext
    void check(@Observes StartupEvent startup) {
        int segments = settings.pathSegments();
        if (segments < 1 || segments > MultisiteSettingsView.MAX_PATH_SEGMENTS) {
            throw new IllegalStateException("quarkpress.multisite.path-segments is " + segments
                    + "; it must be between 1 and " + MultisiteSettingsView.MAX_PATH_SEGMENTS);
        }
        long id = persistence.mainSiteId();
        if (id <= 0) {
            throw new IllegalStateException(
                    "quarkpress.main-site-id is " + id + "; it must be the positive id of a site in qp_site");
        }
        if (directory.find(SiteId.of(id)).isEmpty()) {
            throw new IllegalStateException("quarkpress.main-site-id is " + id + ", but qp_site has no site with"
                    + " that id; the main site's domain and path are the network's address");
        }
    }
}

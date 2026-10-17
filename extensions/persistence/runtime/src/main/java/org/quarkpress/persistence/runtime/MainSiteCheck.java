package org.quarkpress.persistence.runtime;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.quarkpress.core.SiteDirectory;
import org.quarkpress.core.SiteId;

/**
 * Without the multisite module every request belongs to the main site, so the application does not start unless that
 * site exists (checked after the migrations ran).
 */
@ApplicationScoped
@IfBuildProperty(name = "quarkpress.multisite.enabled", stringValue = "false", enableIfMissing = true)
public class MainSiteCheck {

    static final String KEY = "quarkpress.main-site-id";

    @Inject
    QuarkPressRuntimeConfig config;

    @Inject
    SiteDirectory directory;

    @ActivateRequestContext
    void check(@Observes StartupEvent startup) {
        long id = config.mainSiteId();
        if (id <= 0) {
            throw new IllegalStateException(KEY + " is " + id + "; it must be the positive id of a site in qp_site");
        }
        if (directory.find(SiteId.of(id)).isEmpty()) {
            throw new IllegalStateException(KEY + " is " + id + ", but qp_site has no site with that id; create the"
                    + " site or set " + KEY + " to the id of an existing site");
        }
    }
}

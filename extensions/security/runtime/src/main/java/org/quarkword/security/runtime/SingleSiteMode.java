package org.quarkword.security.runtime;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;

/** Without the multisite module: one site, no network administrators. */
@ApplicationScoped
@IfBuildProperty(name = "quarkword.multisite.enabled", stringValue = "false", enableIfMissing = true)
public class SingleSiteMode implements InstallMode {

    @Override
    public boolean multisite() {
        return false;
    }
}

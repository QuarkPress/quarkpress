package org.quarkword.security.runtime;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;

/** With the multisite module: a network of sites with network administrators. */
@ApplicationScoped
@IfBuildProperty(name = "quarkword.multisite.enabled", stringValue = "true")
public class MultisiteMode implements InstallMode {

    @Override
    public boolean multisite() {
        return true;
    }
}

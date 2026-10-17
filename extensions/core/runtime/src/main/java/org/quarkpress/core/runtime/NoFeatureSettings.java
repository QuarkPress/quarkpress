package org.quarkpress.core.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import org.quarkpress.core.FeatureSettings;
import org.quarkpress.core.SiteId;

/** Used when no module stores feature switches: nothing is set, so every module default applies. */
@DefaultBean
@ApplicationScoped
public class NoFeatureSettings implements FeatureSettings {

    @Override
    public Map<String, Boolean> siteValues(SiteId site) {
        return Map.of();
    }

    @Override
    public Map<String, Boolean> networkDefaults() {
        return Map.of();
    }
}

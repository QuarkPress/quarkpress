package org.quarkword.core.deployment.gate;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.quarkword.core.FeatureSettings;
import org.quarkword.core.SiteId;

@ApplicationScoped
public class MutableSettings implements FeatureSettings {

    private final Map<String, Boolean> site = new ConcurrentHashMap<>();

    @Override
    public Map<String, Boolean> siteValues(SiteId siteId) {
        return Map.copyOf(site);
    }

    @Override
    public Map<String, Boolean> networkDefaults() {
        return Map.of();
    }

    public void set(String key, boolean enabled) {
        site.put(key, enabled);
    }

    public void clear() {
        site.clear();
    }
}

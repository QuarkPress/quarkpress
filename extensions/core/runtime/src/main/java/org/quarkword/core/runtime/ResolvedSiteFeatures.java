package org.quarkword.core.runtime;

import java.util.List;
import java.util.Map;
import org.quarkword.core.FeatureValue;
import org.quarkword.core.SiteFeatures;

final class ResolvedSiteFeatures implements SiteFeatures {

    private final Map<String, FeatureValue> values;

    ResolvedSiteFeatures(Map<String, FeatureValue> values) {
        this.values = values;
    }

    @Override
    public boolean isEnabled(String key) {
        return value(key).enabled();
    }

    @Override
    public FeatureValue value(String key) {
        FeatureValue value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "unknown QuarkWord feature '" + key + "'; installed features: " + values.keySet());
        }
        return value;
    }

    @Override
    public List<FeatureValue> all() {
        return List.copyOf(values.values());
    }
}

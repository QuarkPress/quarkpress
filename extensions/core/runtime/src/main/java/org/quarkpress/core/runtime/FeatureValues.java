package org.quarkpress.core.runtime;

import java.util.Map;
import org.quarkpress.core.FeatureDescriptor;
import org.quarkpress.core.FeatureValue;
import org.quarkpress.core.FeatureValue.Source;

/** The precedence of feature switches: the site's own value, then the network default, then the module default. */
public final class FeatureValues {

    private FeatureValues() {}

    public static FeatureValue resolve(
            FeatureDescriptor feature, Map<String, Boolean> site, Map<String, Boolean> network) {
        Boolean siteValue = site.get(feature.key());
        if (siteValue != null) {
            return new FeatureValue(feature.key(), siteValue, Source.SITE);
        }
        Boolean networkValue = network.get(feature.key());
        if (networkValue != null) {
            return new FeatureValue(feature.key(), networkValue, Source.NETWORK);
        }
        return new FeatureValue(feature.key(), feature.defaultEnabled(), Source.MODULE);
    }
}

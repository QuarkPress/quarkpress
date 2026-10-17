package org.quarkpress.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.FeatureDescriptor;
import org.quarkpress.core.FeatureValue;
import org.quarkpress.core.FeatureValue.Source;

class FeatureValuesTest {

    /** Every combination: site absent/false/true x network absent/false/true x module default false/true. */
    @Test
    void siteBeatsNetworkBeatsModule() {
        Boolean[] states = {null, false, true};
        for (Boolean site : states) {
            for (Boolean network : states) {
                for (boolean moduleDefault : new boolean[] {false, true}) {
                    FeatureDescriptor feature = new FeatureDescriptor("demo", "test", moduleDefault, "Demo");
                    Map<String, Boolean> siteValues = new HashMap<>();
                    Map<String, Boolean> networkValues = new HashMap<>();
                    if (site != null) {
                        siteValues.put("demo", site);
                    }
                    if (network != null) {
                        networkValues.put("demo", network);
                    }
                    FeatureValue value = FeatureValues.resolve(feature, siteValues, networkValues);
                    if (site != null) {
                        assertThat(value).isEqualTo(new FeatureValue("demo", site, Source.SITE));
                    } else if (network != null) {
                        assertThat(value).isEqualTo(new FeatureValue("demo", network, Source.NETWORK));
                    } else {
                        assertThat(value).isEqualTo(new FeatureValue("demo", moduleDefault, Source.MODULE));
                    }
                }
            }
        }
    }

    @Test
    void otherKeysDoNotMatter() {
        FeatureDescriptor feature = new FeatureDescriptor("demo", "test", false, "Demo");
        FeatureValue value = FeatureValues.resolve(feature, Map.of("other", true), Map.of("other", true));
        assertThat(value).isEqualTo(new FeatureValue("demo", false, Source.MODULE));
    }
}

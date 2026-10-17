package org.quarkpress.core;

import java.util.List;

/** The per-site features of the installed modules, as they apply to one site. */
public interface SiteFeatures {

    /** Whether the feature is on. Throws IllegalArgumentException for a key no installed module declares. */
    boolean isEnabled(String key);

    /**
     * The feature's value and where it comes from (the site, the network default or the module default). Throws
     * IllegalArgumentException for a key no installed module declares.
     */
    FeatureValue value(String key);

    /** Every installed feature, sorted by key. */
    List<FeatureValue> all();
}

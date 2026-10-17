package org.quarkpress.core;

import java.util.Map;

/**
 * Where per-site feature switches are stored. The persistence module keeps them in options; without it nothing is
 * stored and module defaults apply.
 */
public interface FeatureSettings {

    /** The switches a site sets explicitly: feature key to enabled. */
    Map<String, Boolean> siteValues(SiteId site);

    /** The network defaults: feature key to enabled. */
    Map<String, Boolean> networkDefaults();
}

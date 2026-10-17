package org.quarkpress.core;

import java.util.Objects;

/** The effective state of a feature for a site, and where it comes from. */
public record FeatureValue(String key, boolean enabled, Source source) {

    public enum Source {
        /** Set on the site. */
        SITE,
        /** Network default. */
        NETWORK,
        /** Default of the declaring module. */
        MODULE,
        /** No site is resolved, so every feature is off. */
        NO_SITE
    }

    public FeatureValue {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(source, "source");
    }
}

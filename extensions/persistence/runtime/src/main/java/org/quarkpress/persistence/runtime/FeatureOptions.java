package org.quarkpress.persistence.runtime;

/** Option names that hold feature switches. */
public final class FeatureOptions {

    /** Site option: JSON object of feature key to boolean. */
    public static final String SITE = "qp_features";

    /** Network option: JSON object of feature key to boolean. */
    public static final String NETWORK = "qp_feature_defaults";

    private FeatureOptions() {}
}

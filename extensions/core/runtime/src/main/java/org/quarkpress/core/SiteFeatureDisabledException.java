package org.quarkpress.core;

/** Thrown by a {@link SiteFeature} method whose feature is disabled for the current site. */
public class SiteFeatureDisabledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String key;

    public SiteFeatureDisabledException(String key) {
        super("QuarkPress feature '" + key + "' is disabled for the current site");
        this.key = key;
    }

    public String key() {
        return key;
    }
}

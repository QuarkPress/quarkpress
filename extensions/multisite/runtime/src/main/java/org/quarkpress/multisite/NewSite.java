package org.quarkpress.multisite;

import java.util.Map;
import java.util.Objects;

/**
 * A site to create. Domain and path are normalized as WordPress normalizes them (and lower-cased); an empty title
 * becomes {@code Site <id>} (as when WordPress is given no title), a blank one is kept. {@code features} sets per-site
 * feature switches from the start; features it does not name follow the network defaults.
 *
 * @param publicFlag WordPress's {@code public} value: 1 indexable, 0 not, negative values for privacy modules
 */
public record NewSite(String domain, String path, String title, int publicFlag, Map<String, Boolean> features) {

    public NewSite {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(title, "title");
        features = Map.copyOf(Objects.requireNonNull(features, "features"));
        requirePublic(publicFlag);
    }

    /** A public site (public 1, as the network admin's Add Site creates it) with no feature switches of its own. */
    public static NewSite of(String domain, String path, String title) {
        return new NewSite(domain, path, title, 1, Map.of());
    }

    /** {@code value}, refused unless it fits WordPress's {@code public} column (a small integer). */
    public static int requirePublic(int value) {
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException("public must be between -32768 and 32767: " + value);
        }
        return value;
    }

    public NewSite withFeatures(Map<String, Boolean> switches) {
        return new NewSite(domain, path, title, publicFlag, switches);
    }

    public NewSite withPublic(int value) {
        return new NewSite(domain, path, title, value, features);
    }
}

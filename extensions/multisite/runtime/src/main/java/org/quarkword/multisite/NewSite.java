package org.quarkword.multisite;

import java.util.Map;
import java.util.Objects;

/**
 * A site to create. Domain and path are normalized as WordPress normalizes them (and lower-cased); an empty title
 * becomes {@code Site <id>} (as when WordPress is given no title), a blank one is kept. {@code features} sets per-site
 * feature switches from the start; features it does not name follow the network defaults. {@code scheme} is the one
 * the site's {@code home} and {@code siteurl} options start with: {@code http}, or {@code https} when the creating
 * request is https.
 *
 * @param publicFlag WordPress's {@code public} value: 1 indexable, 0 not, negative values for privacy modules
 * @param scheme {@code http} or {@code https}
 */
public record NewSite(
        String domain, String path, String title, int publicFlag, Map<String, Boolean> features, String scheme) {

    public static final String HTTP = "http";
    public static final String HTTPS = "https";

    public NewSite {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(title, "title");
        features = Map.copyOf(Objects.requireNonNull(features, "features"));
        requirePublic(publicFlag);
        requireScheme(scheme);
    }

    /** A site whose {@code home} and {@code siteurl} start with {@code http}. */
    public NewSite(String domain, String path, String title, int publicFlag, Map<String, Boolean> features) {
        this(domain, path, title, publicFlag, features, HTTP);
    }

    /** A public site (public 1, as the network admin's Add Site creates it) with no feature switches of its own. */
    public static NewSite of(String domain, String path, String title) {
        return of(domain, path, title, HTTP);
    }

    /** {@link #of(String, String, String)} with the given scheme for {@code home} and {@code siteurl}. */
    public static NewSite of(String domain, String path, String title, String scheme) {
        return new NewSite(domain, path, title, 1, Map.of(), scheme);
    }

    /** {@code scheme}, refused unless it is {@code http} or {@code https}. */
    public static String requireScheme(String scheme) {
        Objects.requireNonNull(scheme, "scheme");
        if (!scheme.equals(HTTP) && !scheme.equals(HTTPS)) {
            throw new IllegalArgumentException("scheme must be http or https: " + scheme);
        }
        return scheme;
    }

    /** {@code value}, refused unless it fits WordPress's {@code public} column (a small integer). */
    public static int requirePublic(int value) {
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException("public must be between -32768 and 32767: " + value);
        }
        return value;
    }

    public NewSite withFeatures(Map<String, Boolean> switches) {
        return new NewSite(domain, path, title, publicFlag, switches, scheme);
    }

    public NewSite withPublic(int value) {
        return new NewSite(domain, path, title, value, features, scheme);
    }
}

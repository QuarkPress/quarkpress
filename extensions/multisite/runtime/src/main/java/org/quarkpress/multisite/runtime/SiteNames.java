package org.quarkpress.multisite.runtime;

import java.util.Objects;

/** The size rule of site names: at most 255 characters (code points), what the name column holds. */
final class SiteNames {

    static final int MAX_CODE_POINTS = 255;

    private SiteNames() {}

    /** The name, refused when it is longer than the column holds. */
    static String require(String name) {
        Objects.requireNonNull(name, "name");
        if (name.codePointCount(0, name.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("a site name has at most " + MAX_CODE_POINTS + " characters");
        }
        return name;
    }

    /** The text cut to the characters the column holds. */
    static String firstCodePoints(String text) {
        if (text.codePointCount(0, text.length()) <= MAX_CODE_POINTS) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_CODE_POINTS));
    }
}

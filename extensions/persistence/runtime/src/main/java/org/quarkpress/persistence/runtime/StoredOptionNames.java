package org.quarkpress.persistence.runtime;

import org.quarkpress.core.runtime.OptionNames;

/**
 * Option names as the store takes them: already normalized by the caller. A name with leading or trailing whitespace
 * is refused before any query, so both databases answer alike (MySQL would otherwise match {@code "foo "} with
 * {@code "foo"}).
 */
final class StoredOptionNames {

    private StoredOptionNames() {}

    static String require(String name) {
        String normalized = OptionNames.normalize(name);
        if (!normalized.equals(name)) {
            throw new IllegalArgumentException(
                    "option name has leading or trailing whitespace; the store takes normalized names");
        }
        return name;
    }
}

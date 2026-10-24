package org.quarkpress.testing.wpscenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A revision as one side observed it, normalized: field name to text, in recording order. */
public record ObservedRevision(Map<String, String> fields) {

    /** A revision that no longer exists. */
    public static final ObservedRevision DELETED = new ObservedRevision(Map.of("deleted", "yes"));

    public ObservedRevision {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}

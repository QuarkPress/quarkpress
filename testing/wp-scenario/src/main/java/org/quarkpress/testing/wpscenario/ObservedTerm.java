package org.quarkpress.testing.wpscenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A term as one side observed it, normalized: field name to text, in recording order. */
public record ObservedTerm(Map<String, String> fields) {

    /** A term that no longer exists. */
    public static final ObservedTerm DELETED = new ObservedTerm(Map.of("deleted", "yes"));

    public ObservedTerm {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}

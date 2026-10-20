package org.quarkpress.testing.wpscenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A post as one side observed it, normalized: field name to text, in recording order. */
public record ObservedPost(Map<String, String> fields) {

    /** A post that no longer exists. */
    public static final ObservedPost DELETED = new ObservedPost(Map.of("deleted", "yes"));

    public ObservedPost {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}

package org.quarkpress.lib.wxr;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Defensive copies for the records: insertion order kept, unmodifiable, null becomes empty. */
final class Copies {

    private Copies() {}

    static Map<String, String> map(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    static <T> List<T> list(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}

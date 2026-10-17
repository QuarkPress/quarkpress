package org.quarkpress.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** The per-site features of the installed modules, fixed at build time. */
public final class FeatureRegistry {

    private final Map<String, FeatureDescriptor> byKey;
    private final List<FeatureDescriptor> sorted;

    public FeatureRegistry(Collection<FeatureDescriptor> features) {
        Map<String, FeatureDescriptor> ordered = new TreeMap<>();
        for (FeatureDescriptor feature : features) {
            FeatureDescriptor previous = ordered.putIfAbsent(feature.key(), feature);
            if (previous != null) {
                throw new IllegalArgumentException("QuarkPress feature '" + feature.key() + "' is declared twice: by "
                        + previous.module() + " and by " + feature.module());
            }
        }
        this.byKey = Map.copyOf(ordered);
        this.sorted = List.copyOf(ordered.values());
    }

    public Optional<FeatureDescriptor> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public boolean contains(String key) {
        return byKey.containsKey(key);
    }

    /** All features, sorted by key. */
    public List<FeatureDescriptor> all() {
        return sorted;
    }
}

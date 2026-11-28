package org.quarkword.lib.wpstyleengine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Named style stores, created on first use and listed in creation order. WordPress keeps one such registry for the
 * whole process; here the caller owns one, typically per request.
 *
 * <p>Mutable and not thread-safe.
 */
public final class StyleStores {

    private final LinkedHashMap<String, StyleStore> stores = new LinkedHashMap<>();

    /**
     * The store called {@code name}, created when it does not exist yet.
     *
     * @throws IllegalArgumentException when {@code name} is {@code ""} or {@code "0"}
     */
    public StyleStore store(String name) {
        Objects.requireNonNull(name, "name");
        if (Php.isEmpty(name)) {
            throw new IllegalArgumentException("invalid style store name: \"" + name + "\"");
        }
        return stores.computeIfAbsent(name, StyleStore::new);
    }

    /** The store called {@code name}, if it exists; nothing is created. */
    public Optional<StyleStore> find(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(stores.get(name));
    }

    /** The store names in creation order. */
    public List<String> names() {
        return List.copyOf(stores.keySet());
    }

    /** Removes every store. */
    public void clear() {
        stores.clear();
    }
}

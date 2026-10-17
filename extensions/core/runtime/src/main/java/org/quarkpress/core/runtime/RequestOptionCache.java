package org.quarkpress.core.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteId;

/**
 * Options read during the current request, kept per site so two sites never share an entry. Writers invalidate what
 * they change. Threads that share the request context may use it concurrently; each site's autoloaded options and each
 * missing name are read from the store once. The store must not call back into this cache.
 */
@RequestScoped
public class RequestOptionCache {

    @Inject
    OptionStore store;

    private final Map<SiteId, Map<String, JsonNode>> autoloaded = new ConcurrentHashMap<>();
    private final Map<SiteId, Map<String, Optional<JsonNode>>> single = new ConcurrentHashMap<>();

    /** The value of an option whose name is already normalized; the result is a copy. */
    public Optional<JsonNode> get(SiteId site, String name) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(name, "name");
        JsonNode hit = autoloaded.computeIfAbsent(site, this::readAutoloaded).get(name);
        if (hit != null) {
            return Optional.of(hit.deepCopy());
        }
        return single.computeIfAbsent(site, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(
                        name,
                        key -> Objects.requireNonNull(store.find(site, key), "store result")
                                .map(JsonNode::deepCopy))
                .map(JsonNode::deepCopy);
    }

    /** Copies on intake too, so the store keeps no handle on what the cache holds. */
    private Map<String, JsonNode> readAutoloaded(SiteId site) {
        Map<String, JsonNode> copies = new HashMap<>();
        store.autoloaded(site).forEach((name, value) -> copies.put(name, value.deepCopy()));
        return Map.copyOf(copies);
    }

    /** Forgets {@code name} for {@code site}, and the site's autoloaded set, which may hold or gain it. */
    public void invalidate(SiteId site, String name) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(name, "name");
        autoloaded.remove(site);
        Map<String, Optional<JsonNode>> names = single.get(site);
        if (names != null) {
            names.remove(name);
        }
    }
}

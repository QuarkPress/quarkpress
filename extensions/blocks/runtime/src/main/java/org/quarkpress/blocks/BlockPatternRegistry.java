package org.quarkpress.blocks;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.blocks.runtime.Registries;

/**
 * The registered block patterns, read at start-up from every {@link BlockPatternProvider} bean. A pattern registered
 * again under the same name replaces the earlier one where it stood.
 */
@Startup
@ApplicationScoped
public class BlockPatternRegistry {

    @Inject
    @Any
    Instance<BlockPatternProvider> providers;

    private Map<String, BlockPattern> patterns;

    @PostConstruct
    void register() {
        patterns = Registries.replacingInPlace(
                Registries.read(providers, BlockPatternProvider::order, BlockPatternProvider::patterns),
                BlockPattern::name);
    }

    /** Every registered pattern, in registration order. */
    public List<BlockPattern> all() {
        return List.copyOf(patterns.values());
    }

    /** The pattern registered under exactly this name. */
    public Optional<BlockPattern> find(String name) {
        return Optional.ofNullable(patterns.get(name));
    }
}

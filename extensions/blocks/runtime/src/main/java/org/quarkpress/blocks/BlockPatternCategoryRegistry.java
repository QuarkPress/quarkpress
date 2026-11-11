package org.quarkpress.blocks;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.quarkpress.blocks.runtime.Registries;

/**
 * The registered block pattern categories, read at start-up from every {@link BlockPatternCategoryProvider} bean: the
 * core categories first, then the application's. A category registered again under the same name replaces the
 * earlier one where it stood.
 */
@Startup
@ApplicationScoped
public class BlockPatternCategoryRegistry {

    @Inject
    @Any
    Instance<BlockPatternCategoryProvider> providers;

    private Map<String, BlockPatternCategory> categories;

    @PostConstruct
    void register() {
        categories = Registries.replacingInPlace(
                Registries.read(
                        providers, BlockPatternCategoryProvider::order, BlockPatternCategoryProvider::categories),
                BlockPatternCategory::name);
    }

    /** Every registered category, in registration order. */
    public List<BlockPatternCategory> all() {
        return List.copyOf(categories.values());
    }
}

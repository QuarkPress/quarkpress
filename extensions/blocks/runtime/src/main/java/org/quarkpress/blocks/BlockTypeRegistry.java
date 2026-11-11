package org.quarkpress.blocks;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.blocks.runtime.Registries;

/**
 * The registered block types and their renderers, read at start-up from every {@link BlockTypeProvider} and
 * {@link BlockRenderer} bean. A type registered twice, two renderers for one type and a renderer for a type nobody
 * registered each stop the application.
 */
@Startup
@ApplicationScoped
public class BlockTypeRegistry {

    @Inject
    @Any
    Instance<BlockTypeProvider> providers;

    @Inject
    @Any
    Instance<BlockRenderer> renderers;

    private Map<String, BlockType> types;
    private Map<String, BlockRenderer> renderersByName;

    @PostConstruct
    void register() {
        Map<String, BlockType> registered = new LinkedHashMap<>();
        for (BlockType type : Registries.read(providers, BlockTypeProvider::order, BlockTypeProvider::blockTypes)) {
            if (registered.putIfAbsent(type.name(), type) != null) {
                throw new IllegalStateException(
                        "QuarkPress: the block type " + type.name() + " is registered more than once");
            }
        }
        // the renderers are kept and used for every rendering, so a @Dependent one lives as long as this registry
        Map<String, BlockRenderer> byName = new HashMap<>();
        for (BlockRenderer renderer : renderers) {
            String name = renderer.blockName();
            if (byName.putIfAbsent(name, renderer) != null) {
                throw new IllegalStateException("QuarkPress: two block renderers for " + name);
            }
        }
        for (String name : byName.keySet()) {
            if (!registered.containsKey(name)) {
                throw new IllegalStateException(
                        "QuarkPress: the block renderer for " + name + " names no registered block type");
            }
        }
        this.types = registered;
        this.renderersByName = byName;
    }

    /** Every registered type, providers by order then class name, each provider's types in its order. */
    public List<BlockType> all() {
        return List.copyOf(types.values());
    }

    /** The type registered under exactly this name. */
    public Optional<BlockType> find(String name) {
        return Optional.ofNullable(types.get(name));
    }

    /** The renderer of the registered type of this name. */
    public Optional<BlockRenderer> renderer(String name) {
        return Optional.ofNullable(renderersByName.get(name));
    }

    /** Whether the registered type of this name is rendered on the server. */
    public boolean isDynamic(String name) {
        return renderersByName.containsKey(name);
    }
}

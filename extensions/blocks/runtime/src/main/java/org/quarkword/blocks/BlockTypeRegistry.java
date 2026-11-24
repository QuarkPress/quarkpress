package org.quarkword.blocks;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.quarkword.blocks.runtime.Registries;

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
    private Map<String, ObjectNode> attributesByName;

    @PostConstruct
    void register() {
        Map<String, BlockType> registered = new LinkedHashMap<>();
        for (BlockType type : Registries.read(providers, BlockTypeProvider::order, BlockTypeProvider::blockTypes)) {
            if (registered.putIfAbsent(type.name(), type) != null) {
                throw new IllegalStateException(
                        "QuarkWord: the block type " + type.name() + " is registered more than once");
            }
        }
        // the renderers are kept and used for every rendering, so a @Dependent one lives as long as this registry
        Map<String, BlockRenderer> byName = new HashMap<>();
        for (BlockRenderer renderer : renderers) {
            String name = renderer.blockName();
            if (byName.putIfAbsent(name, renderer) != null) {
                throw new IllegalStateException("QuarkWord: two block renderers for " + name);
            }
        }
        for (String name : byName.keySet()) {
            if (!registered.containsKey(name)) {
                throw new IllegalStateException(
                        "QuarkWord: the block renderer for " + name + " names no registered block type");
            }
        }
        // every type is registered at start-up, so every type gets the attributes of its supports
        Map<String, ObjectNode> attributes = new HashMap<>();
        for (BlockType type : registered.values()) {
            attributes.put(type.name(), BlockSupports.attributes(type));
        }
        this.types = registered;
        this.renderersByName = byName;
        this.attributesByName = attributes;
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

    /**
     * The attributes of the registered {@code type} as WordPress answers them: its declared ones, {@code lock},
     * {@code metadata} and those its supports add ({@link BlockSupports}), computed once at start-up. A new object on
     * each call; a type that is not the registered one of its name is refused.
     */
    public ObjectNode attributes(BlockType type) {
        if (types.get(type.name()) != type) {
            throw new IllegalArgumentException("QuarkWord: " + type.name() + " is not a registered block type");
        }
        return attributesByName.get(type.name()).deepCopy();
    }

    /** Whether the registered type of this name is rendered on the server. */
    public boolean isDynamic(String name) {
        return renderersByName.containsKey(name);
    }
}

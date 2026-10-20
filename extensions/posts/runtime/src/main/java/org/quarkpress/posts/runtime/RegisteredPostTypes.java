package org.quarkpress.posts.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.security.PostTypeCapabilities;

/** The post types of this build, with each type's capability names built once. */
public class RegisteredPostTypes implements PostTypes {

    private final List<PostTypeDefinition> all;
    private final Map<String, PostTypeDefinition> byKey = new LinkedHashMap<>();
    private final Map<String, PostTypeCapabilities> capabilities = new LinkedHashMap<>();
    private final List<String> searchable;

    public RegisteredPostTypes(List<PostTypeDefinition> types) {
        this.all = List.copyOf(types);
        for (PostTypeDefinition type : all) {
            byKey.put(type.key(), type);
            capabilities.put(
                    type.key(),
                    PostTypeCapabilities.of(
                            type.key(),
                            type.capabilityType(),
                            type.capabilityTypePlural(),
                            type.mapMetaCap(),
                            type.capabilities()));
        }
        this.searchable = all.stream()
                .filter(type -> !type.excludeFromSearch())
                .map(PostTypeDefinition::key)
                .toList();
    }

    @Override
    public List<PostTypeDefinition> all() {
        return all;
    }

    @Override
    public Optional<PostTypeDefinition> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    @Override
    public boolean registered(String key) {
        return byKey.containsKey(key);
    }

    @Override
    public boolean hierarchical(String key) {
        PostTypeDefinition type = byKey.get(key);
        return type != null && type.hierarchical();
    }

    @Override
    public boolean supports(String key, String feature) {
        PostTypeDefinition type = byKey.get(key);
        return type != null && type.supports(feature);
    }

    @Override
    public List<String> searchable() {
        return searchable;
    }

    @Override
    public Optional<PostTypeCapabilities> capabilities(String key) {
        return Optional.ofNullable(capabilities.get(key));
    }

    @Override
    public PostTypeCapabilities capabilitiesOrPost(String key) {
        return capabilities.getOrDefault(key, PostTypeCapabilities.POST);
    }
}

package org.quarkpress.posts.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.security.PostVisibility;

/** The post statuses of this build. */
public class RegisteredPostStatuses implements PostStatuses {

    private final List<PostStatusDefinition> all;
    private final Map<String, PostStatusDefinition> byName = new LinkedHashMap<>();

    public RegisteredPostStatuses(List<PostStatusDefinition> statuses) {
        this.all = List.copyOf(statuses);
        all.forEach(status -> byName.put(status.name(), status));
    }

    @Override
    public List<PostStatusDefinition> all() {
        return all;
    }

    @Override
    public Optional<PostStatusDefinition> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public boolean registered(String name) {
        return byName.containsKey(name);
    }

    @Override
    public boolean floating(String name) {
        PostStatusDefinition status = byName.get(name);
        return status != null && status.dateFloating();
    }

    @Override
    public List<String> publicStatuses() {
        return all.stream()
                .filter(PostStatusDefinition::publicly)
                .map(PostStatusDefinition::name)
                .toList();
    }

    @Override
    public List<String> privateStatuses() {
        return all.stream()
                .filter(PostStatusDefinition::privateStatus)
                .map(PostStatusDefinition::name)
                .toList();
    }

    @Override
    public List<String> excludedFromSearch() {
        return all.stream()
                .filter(PostStatusDefinition::excludeFromSearch)
                .map(PostStatusDefinition::name)
                .toList();
    }

    @Override
    public PostVisibility visibility(String name) {
        PostStatusDefinition status = byName.get(name);
        if (status == null) {
            return PostVisibility.UNREGISTERED;
        }
        if (status.publicly()) {
            return PostVisibility.PUBLIC;
        }
        return status.privateStatus() ? PostVisibility.PRIVATE : PostVisibility.OTHER;
    }
}

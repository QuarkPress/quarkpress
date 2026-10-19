package org.quarkpress.security.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/** A mapping context whose answers a test sets. */
final class FakeContext implements MapContext {

    boolean multisite;
    Optional<UserId> user = Optional.empty();
    boolean superAdmin;
    final Set<Long> superAdmins = new HashSet<>();
    final Set<String> held = new HashSet<>();
    final Map<String, JsonNode> siteOptions = new HashMap<>();
    final Map<String, JsonNode> networkOptions = new HashMap<>();
    boolean disallowUnfilteredHtml;

    static FakeContext single(long user) {
        FakeContext context = new FakeContext();
        context.user = user == 0 ? Optional.empty() : Optional.of(UserId.of(user));
        return context;
    }

    static FakeContext network(long user) {
        FakeContext context = single(user);
        context.multisite = true;
        return context;
    }

    FakeContext superAdmin() {
        superAdmin = true;
        user.ifPresent(id -> superAdmins.add(id.value()));
        return this;
    }

    FakeContext holding(String... capabilities) {
        held.addAll(Set.of(capabilities));
        return this;
    }

    FakeContext siteOption(String name, JsonNode value) {
        siteOptions.put(name, value);
        return this;
    }

    FakeContext networkOption(String name, JsonNode value) {
        networkOptions.put(name, value);
        return this;
    }

    @Override
    public boolean multisite() {
        return multisite;
    }

    @Override
    public Optional<UserId> user() {
        return user;
    }

    @Override
    public Optional<SiteId> site() {
        return Optional.of(SiteId.of(1));
    }

    @Override
    public boolean isSuperAdmin() {
        return superAdmin;
    }

    @Override
    public boolean isSuperAdmin(UserId other) {
        return superAdmins.contains(other.value());
    }

    @Override
    public boolean userCan(String capability) {
        return (multisite && superAdmin) || held.contains(capability);
    }

    @Override
    public Optional<JsonNode> siteOption(String name) {
        return Optional.ofNullable(siteOptions.get(name));
    }

    @Override
    public Optional<JsonNode> networkOption(String name) {
        return Optional.ofNullable(networkOptions.get(name));
    }

    @Override
    public boolean disallowUnfilteredHtml() {
        return disallowUnfilteredHtml;
    }
}

package org.quarkpress.security.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.security.Roles;

/** The default role table recorded at build time. */
public final class RegisteredRoles implements Roles {

    private final List<RoleDefinition> defaults;
    private final Map<String, RoleDefinition> byKey;

    public RegisteredRoles(List<RoleDefinition> defaults) {
        this.defaults = List.copyOf(defaults);
        this.byKey = Map.copyOf(RoleTables.byKey(defaults));
    }

    @Override
    public List<RoleDefinition> defaults() {
        return defaults;
    }

    @Override
    public Optional<RoleDefinition> defaultRole(String key) {
        return Optional.ofNullable(byKey.get(key));
    }
}

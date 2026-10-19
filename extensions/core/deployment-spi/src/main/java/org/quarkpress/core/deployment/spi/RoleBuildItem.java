package org.quarkpress.core.deployment.spi;

import io.quarkus.builder.item.MultiBuildItem;
import java.util.Objects;
import org.quarkpress.core.RoleDefinition;

/**
 * Registers a role that every site has until its role table is changed (after WordPress's five roles, in key order).
 * A key that another role already has fails the build.
 */
public final class RoleBuildItem extends MultiBuildItem {

    private final RoleDefinition role;

    public RoleBuildItem(RoleDefinition role) {
        this.role = Objects.requireNonNull(role, "role");
    }

    public RoleDefinition role() {
        return role;
    }
}

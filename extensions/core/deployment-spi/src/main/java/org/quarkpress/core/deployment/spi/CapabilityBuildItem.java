package org.quarkpress.core.deployment.spi;

import io.quarkus.builder.item.MultiBuildItem;
import java.util.Objects;
import java.util.Set;

/**
 * Grants a capability to roles in the default role table (WordPress's roles or roles registered with
 * {@link RoleBuildItem}); the capability is appended to each role. A role no module registers fails the build.
 */
public final class CapabilityBuildItem extends MultiBuildItem {

    private final String capability;
    private final Set<String> grantedToRoles;

    public CapabilityBuildItem(String capability, Set<String> grantedToRoles) {
        this.capability = Objects.requireNonNull(capability, "capability");
        this.grantedToRoles = Set.copyOf(Objects.requireNonNull(grantedToRoles, "grantedToRoles"));
        if (capability.isEmpty()) {
            throw new IllegalArgumentException("a capability name must not be empty");
        }
        if (this.grantedToRoles.isEmpty()) {
            throw new IllegalArgumentException("the capability " + capability + " is granted to no role");
        }
    }

    public String capability() {
        return capability;
    }

    public Set<String> grantedToRoles() {
        return grantedToRoles;
    }
}

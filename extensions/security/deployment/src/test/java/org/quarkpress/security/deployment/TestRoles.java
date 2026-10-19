package org.quarkpress.security.deployment;

import io.quarkus.builder.BuildChainBuilder;
import org.quarkpress.core.deployment.spi.CapabilityBuildItem;
import org.quarkpress.core.deployment.spi.RoleBuildItem;

/** Registers roles and capabilities from a test, the way an extension's deployment module would. */
public final class TestRoles {

    private TestRoles() {}

    public static void declare(BuildChainBuilder chain, RoleBuildItem[] roles, CapabilityBuildItem[] capabilities) {
        chain.addBuildStep(context -> {
                    for (RoleBuildItem role : roles) {
                        context.produce(role);
                    }
                    for (CapabilityBuildItem capability : capabilities) {
                        context.produce(capability);
                    }
                })
                .produces(RoleBuildItem.class)
                .produces(CapabilityBuildItem.class)
                .build();
    }
}

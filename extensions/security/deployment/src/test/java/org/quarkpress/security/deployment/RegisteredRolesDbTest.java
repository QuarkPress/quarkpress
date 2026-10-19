package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.deployment.spi.CapabilityBuildItem;
import org.quarkpress.core.deployment.spi.RoleBuildItem;
import org.quarkpress.security.Roles;

class RegisteredRolesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(RegisteredRolesDbTest.class, TestRoles.class)
            .addBuildChainCustomizer(chain -> TestRoles.declare(
                    chain,
                    new RoleBuildItem[] {
                        new RoleBuildItem(RoleDefinition.of("shop_manager", "Shop manager", List.of("read")))
                    },
                    new CapabilityBuildItem[] {
                        new CapabilityBuildItem("manage_shop", Set.of("shop_manager", "administrator"))
                    }));

    @Inject
    Roles roles;

    @Test
    void theDefaultsAreWordPressRolesThenRegisteredOnes() {
        assertThat(roles.defaults())
                .extracting(RoleDefinition::key)
                .containsExactly("administrator", "editor", "author", "contributor", "subscriber", "shop_manager");
        assertThat(roles.defaultRole("shop_manager").orElseThrow().capabilities())
                .containsExactly(Map.entry("read", true), Map.entry("manage_shop", true));
        assertThat(roles.defaultRole("administrator").orElseThrow().capabilities())
                .containsEntry("manage_shop", true);
        assertThat(roles.defaultRole("Editor")).isEmpty();
    }
}

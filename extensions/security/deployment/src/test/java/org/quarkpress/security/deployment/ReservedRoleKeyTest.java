package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.deployment.spi.CapabilityBuildItem;
import org.quarkpress.core.deployment.spi.RoleBuildItem;

class ReservedRoleKeyTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.buildOnly(ReservedRoleKeyTest.class, TestRoles.class)
            .addBuildChainCustomizer(chain -> TestRoles.declare(
                    chain,
                    new RoleBuildItem[] {new RoleBuildItem(RoleDefinition.of("super_admin", "Super", List.of()))},
                    new CapabilityBuildItem[0]))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkPress: the role key super_admin is reserved for network administrators"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

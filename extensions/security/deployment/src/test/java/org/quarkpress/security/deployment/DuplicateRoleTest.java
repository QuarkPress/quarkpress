package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.deployment.spi.CapabilityBuildItem;
import org.quarkpress.core.deployment.spi.RoleBuildItem;

class DuplicateRoleTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.buildOnly(DuplicateRoleTest.class, TestRoles.class)
            .addBuildChainCustomizer(chain -> TestRoles.declare(
                    chain,
                    new RoleBuildItem[] {new RoleBuildItem(RoleDefinition.of("author", "Writer", List.of()))},
                    new CapabilityBuildItem[0]))
            .assertException(failure ->
                    assertThat(Failures.messages(failure)).contains("QuarkPress: the role author is registered twice"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

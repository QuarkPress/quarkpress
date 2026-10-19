package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.deployment.spi.CapabilityBuildItem;
import org.quarkpress.core.deployment.spi.RoleBuildItem;

class UnknownGrantedRoleTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.buildOnly(UnknownGrantedRoleTest.class, TestRoles.class)
            .addBuildChainCustomizer(chain -> TestRoles.declare(chain, new RoleBuildItem[0], new CapabilityBuildItem[] {
                new CapabilityBuildItem("manage_shop", Set.of("shopkeeper"))
            }))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkPress: the capability manage_shop is granted to the unknown role shopkeeper"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

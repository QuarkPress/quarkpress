package org.quarkpress.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.CapabilityCheck;
import org.quarkpress.core.NetworkCapabilities;
import org.quarkpress.core.runtime.OpenCapabilityCheck;

class CapabilityCheckTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            new QuarkusExtensionTest().withApplicationRoot(jar -> jar.addClasses(CapabilityCheckTest.class));

    @Test
    void withoutTheSecurityModuleEveryCallerIsAllowed() {
        CapabilityCheck check = Arc.container().instance(CapabilityCheck.class).get();
        assertThat(Arc.container().instance(CapabilityCheck.class).getBean().getBeanClass())
                .isEqualTo(OpenCapabilityCheck.class);
        check.requireNetwork(NetworkCapabilities.CREATE_SITES);
    }
}

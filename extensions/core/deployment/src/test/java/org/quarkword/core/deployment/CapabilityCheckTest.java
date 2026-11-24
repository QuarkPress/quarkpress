package org.quarkword.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.CapabilityCheck;
import org.quarkword.core.NetworkCapabilities;
import org.quarkword.core.runtime.OpenCapabilityCheck;

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

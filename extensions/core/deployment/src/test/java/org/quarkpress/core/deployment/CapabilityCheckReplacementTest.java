package org.quarkpress.core.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.CapabilityCheck;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.core.NetworkCapabilities;

class CapabilityCheckReplacementTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(CapabilityCheckReplacementTest.class, DenyAll.class));

    @Test
    void anApplicationCheckReplacesTheOpenDefault() {
        CapabilityCheck check = Arc.container().instance(CapabilityCheck.class).get();
        assertThat(Arc.container().instance(CapabilityCheck.class).getBean().getBeanClass())
                .isEqualTo(DenyAll.class);
        assertThatThrownBy(() -> check.requireNetwork(NetworkCapabilities.CREATE_SITES))
                .isInstanceOf(CapabilityDeniedException.class);
    }

    @ApplicationScoped
    public static class DenyAll implements CapabilityCheck {
        @Override
        public void requireNetwork(String capability) {
            throw new CapabilityDeniedException(capability);
        }
    }
}

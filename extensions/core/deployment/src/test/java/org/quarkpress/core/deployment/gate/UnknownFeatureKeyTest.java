package org.quarkpress.core.deployment.gate;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.deployment.Failures;

class UnknownFeatureKeyTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Failures.class, Unknown.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("\"nope\"")
                    .contains("no installed module declares"));

    @ApplicationScoped
    public static class Unknown {
        @SiteFeature("nope")
        public void run() {}
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

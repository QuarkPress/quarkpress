package org.quarkpress.core.deployment.features;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

class DuplicateFeatureTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(TestFeatures.class))
            .addBuildChainCustomizer(chain -> TestFeatures.declare(
                    chain,
                    new SiteFeatureBuildItem("revisions", "revisions", true, "one"),
                    new SiteFeatureBuildItem("revisions", "other-module", true, "two")))
            .assertException(failure -> {
                StringBuilder text = new StringBuilder();
                for (Throwable t = failure; t != null; t = t.getCause()) {
                    text.append(t.getMessage()).append('\n');
                }
                assertThat(text.toString())
                        .contains("revisions")
                        .contains("other-module")
                        .contains("declared twice");
            });

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

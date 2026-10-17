package org.quarkpress.core.deployment.gate.placement;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.deployment.Failures;
import org.quarkpress.core.deployment.features.TestFeatures;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

class RawOverrideTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Failures.class, TestFeatures.class, LBase.class, LChild.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")))
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains("QuarkPress @SiteFeature problems:")
                            .contains(
                                    "org.quarkpress.core.deployment.gate.placement.RawOverrideTest$LChild#run overrides org.quarkpress.core.deployment.gate.placement.RawOverrideTest$LBase#run, which @SiteFeature gates, but is not annotated"));

    public static class LBase {
        @SiteFeature("demo")
        public String run(List<String> values) {
            return "base";
        }
    }

    @ApplicationScoped
    public static class LChild extends LBase {
        @Override
        @SuppressWarnings("rawtypes")
        public String run(List values) {
            return "child";
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

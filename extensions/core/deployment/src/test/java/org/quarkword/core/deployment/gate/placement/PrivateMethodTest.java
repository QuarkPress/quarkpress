package org.quarkword.core.deployment.gate.placement;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteFeature;
import org.quarkword.core.deployment.Failures;
import org.quarkword.core.deployment.features.TestFeatures;
import org.quarkword.core.deployment.spi.SiteFeatureBuildItem;

class PrivateMethodTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Failures.class, TestFeatures.class, GatedBean.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")))
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains("QuarkWord @SiteFeature problems:")
                            .contains(
                                    "@SiteFeature on private method org.quarkword.core.deployment.gate.placement.PrivateMethodTest$GatedBean#hidden cannot gate it"));

    @ApplicationScoped
    public static class GatedBean {
        public String run() {
            return hidden();
        }

        @SiteFeature("demo")
        private String hidden() {
            return "hidden";
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

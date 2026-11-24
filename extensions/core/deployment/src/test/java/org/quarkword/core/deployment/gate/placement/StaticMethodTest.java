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

class StaticMethodTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Failures.class, TestFeatures.class, GatedBean.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")))
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains("QuarkWord @SiteFeature problems:")
                            .contains(
                                    "@SiteFeature on static method org.quarkword.core.deployment.gate.placement.StaticMethodTest$GatedBean#utility cannot gate it"));

    @ApplicationScoped
    public static class GatedBean {
        @SiteFeature("demo")
        public static String utility() {
            return "utility";
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

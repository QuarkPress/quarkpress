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

class AbstractInterfaceMethodTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(
                    jar -> jar.addClasses(Failures.class, TestFeatures.class, Gated.class, GatedBean.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")))
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains("QuarkWord @SiteFeature problems:")
                            .contains(
                                    "@SiteFeature on abstract interface method org.quarkword.core.deployment.gate.placement.AbstractInterfaceMethodTest$Gated#run cannot gate anything"));

    public interface Gated {
        @SiteFeature("demo")
        String run();
    }

    @ApplicationScoped
    public static class GatedBean implements Gated {
        @Override
        public String run() {
            return "run";
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

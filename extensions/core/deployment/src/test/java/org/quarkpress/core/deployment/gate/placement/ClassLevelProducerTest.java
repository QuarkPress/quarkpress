package org.quarkpress.core.deployment.gate.placement;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.deployment.Failures;
import org.quarkpress.core.deployment.features.TestFeatures;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

class ClassLevelProducerTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(
                    jar -> jar.addClasses(Failures.class, TestFeatures.class, Value.class, GatedBean.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")))
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains("QuarkPress @SiteFeature problems:")
                            .contains(
                                    "@SiteFeature cannot gate producer method org.quarkpress.core.deployment.gate.placement.ClassLevelProducerTest$GatedBean#value (covered by the class-level @SiteFeature on org.quarkpress.core.deployment.gate.placement.ClassLevelProducerTest$GatedBean)"));

    public static class Value {}

    @ApplicationScoped
    @SiteFeature("demo")
    public static class GatedBean {
        @Produces
        @Dependent
        Value value() {
            return new Value();
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

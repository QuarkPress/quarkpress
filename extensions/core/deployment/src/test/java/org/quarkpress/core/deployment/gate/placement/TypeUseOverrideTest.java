package org.quarkpress.core.deployment.gate.placement;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.deployment.Failures;
import org.quarkpress.core.deployment.features.TestFeatures;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

class TypeUseOverrideTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(
                    jar -> jar.addClasses(Failures.class, TestFeatures.class, Tag.class, TBase.class, TChild.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")))
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains("QuarkPress @SiteFeature problems:")
                            .contains(
                                    "org.quarkpress.core.deployment.gate.placement.TypeUseOverrideTest$TChild#run overrides org.quarkpress.core.deployment.gate.placement.TypeUseOverrideTest$TBase#run, which @SiteFeature gates, but is not annotated"));

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface Tag {}

    public static class TBase {
        @SiteFeature("demo")
        public String run(@Tag String value) {
            return "base";
        }
    }

    @ApplicationScoped
    public static class TChild extends TBase {
        @Override
        public String run(String value) {
            return "child";
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

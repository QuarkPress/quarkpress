package org.quarkpress.core.deployment.gate.placement;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.deployment.Failures;
import org.quarkpress.core.deployment.features.TestFeatures;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

class GenericDefaultOverrideTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Failures.class, TestFeatures.class, GApi.class, GImpl.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")))
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains("QuarkPress @SiteFeature problems:")
                            .contains(
                                    "org.quarkpress.core.deployment.gate.placement.GenericDefaultOverrideTest$GImpl#run overrides org.quarkpress.core.deployment.gate.placement.GenericDefaultOverrideTest$GApi#run, which @SiteFeature gates, but is not annotated"));

    public interface GApi<T> {
        @SiteFeature("demo")
        default String run(T value) {
            return "default";
        }
    }

    @ApplicationScoped
    public static class GImpl implements GApi<String> {
        @Override
        public String run(String value) {
            return "impl";
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

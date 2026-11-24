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

class GenericDefaultOverrideTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Failures.class, TestFeatures.class, GApi.class, GImpl.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")))
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains("QuarkWord @SiteFeature problems:")
                            .contains(
                                    "org.quarkword.core.deployment.gate.placement.GenericDefaultOverrideTest$GImpl#run overrides org.quarkword.core.deployment.gate.placement.GenericDefaultOverrideTest$GApi#run, which @SiteFeature gates, but is not annotated"));

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

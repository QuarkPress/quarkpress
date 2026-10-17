package org.quarkpress.core.deployment.gate.placement;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.FilterSpi;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.deployment.Failures;
import org.quarkpress.core.deployment.features.TestFeatures;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

class GenericFilterTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(
                    jar -> jar.addClasses(Failures.class, TestFeatures.class, Mapper.class, GatedBean.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")))
            .assertException(
                    failure -> assertThat(Failures.messages(failure))
                            .contains("QuarkPress @SiteFeature problems:")
                            .contains(
                                    "matches filter method org.quarkpress.core.deployment.gate.placement.GenericFilterTest$Mapper#apply: generic filter methods are not supported"));

    @FilterSpi
    public interface Mapper {
        <T, U> T apply(U value);
    }

    @ApplicationScoped
    public static class GatedBean implements Mapper {
        @SiteFeature("demo")
        @Override
        @SuppressWarnings("unchecked")
        public <T, U> T apply(U value) {
            return (T) value;
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

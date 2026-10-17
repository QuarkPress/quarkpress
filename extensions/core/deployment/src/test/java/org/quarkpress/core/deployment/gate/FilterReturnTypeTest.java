package org.quarkpress.core.deployment.gate;

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

class FilterReturnTypeTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(
                    jar -> jar.addClasses(Failures.class, TestFeatures.class, CountFilter.class, Counter.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")))
            .assertException(failure ->
                    assertThat(Failures.messages(failure)).contains("must return the type of its first parameter"));

    @FilterSpi
    public interface CountFilter {
        Integer apply(String text);
    }

    @ApplicationScoped
    public static class Counter implements CountFilter {
        @SiteFeature("demo")
        @Override
        public Integer apply(String text) {
            return text.length();
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

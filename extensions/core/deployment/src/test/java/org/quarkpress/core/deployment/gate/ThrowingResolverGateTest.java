package org.quarkpress.core.deployment.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;
import org.quarkpress.core.deployment.Requests;
import org.quarkpress.core.deployment.features.TestFeatures;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

class ThrowingResolverGateTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    Requests.class, TestFeatures.class, Failing.class, Counted.class, ThrowingResolverGateTest.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", true, "Demo")));

    @ApplicationScoped
    public static class Failing implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            throw new IllegalStateException("resolver down");
        }
    }

    @ApplicationScoped
    public static class Counted {
        private final AtomicInteger calls = new AtomicInteger();

        @SiteFeature("demo")
        public String run() {
            calls.incrementAndGet();
            return "ran";
        }

        public int calls() {
            return calls.get();
        }
    }

    @Inject
    Counted counted;

    @Test
    void aThrowingResolverPropagatesAndTheGatedMethodDoesNotRun() {
        Requests.fresh(() -> assertThatThrownBy(() -> counted.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("resolver down"));
        assertThat(counted.calls()).isZero();
    }
}

package org.quarkpress.core.deployment.resolution;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;
import org.quarkpress.core.deployment.Requests;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.core.runtime.SiteResolution;

class ResolverFailureTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(
                    jar -> jar.addClasses(Requests.class, Failing.class, Answering.class, ResolverFailureTest.class));

    @ApplicationScoped
    @Priority(200)
    public static class Failing implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            throw new IllegalStateException("boom");
        }
    }

    @ApplicationScoped
    @Priority(10)
    public static class Answering implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            return Optional.of(SiteId.of(3));
        }
    }

    @Inject
    SiteResolution resolution;

    @Inject
    SiteBinding binding;

    @Test
    void aFailingResolverStopsTheResolution() {
        assertThatThrownBy(() -> resolution.resolve(SiteRequest.none()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        Requests.fresh(() -> assertThatThrownBy(() -> binding.current())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom"));
    }
}

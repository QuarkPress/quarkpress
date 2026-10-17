package org.quarkpress.core.deployment.resolution;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteNotResolvedException;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;
import org.quarkpress.core.deployment.Requests;
import org.quarkpress.core.runtime.SiteBinding;

class ReentrantResolverTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Requests.class, Nosy.class, ReentrantResolverTest.class));

    @ApplicationScoped
    public static class Nosy implements SiteResolver {
        @Inject
        SiteBinding binding;

        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            binding.current();
            return Optional.empty();
        }
    }

    @Inject
    SiteBinding binding;

    @Test
    void aResolverMayNotReadTheCurrentSite() {
        Requests.fresh(() -> assertThatThrownBy(() -> binding.current())
                .isInstanceOf(SiteNotResolvedException.class)
                .hasMessage("a SiteResolver must not read the current site"));
    }
}

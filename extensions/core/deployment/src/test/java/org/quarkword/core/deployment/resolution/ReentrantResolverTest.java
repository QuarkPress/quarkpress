package org.quarkword.core.deployment.resolution;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteId;
import org.quarkword.core.SiteNotResolvedException;
import org.quarkword.core.SiteRequest;
import org.quarkword.core.SiteResolver;
import org.quarkword.core.deployment.Requests;
import org.quarkword.core.runtime.SiteBinding;

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

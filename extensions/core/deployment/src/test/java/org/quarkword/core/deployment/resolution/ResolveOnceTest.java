package org.quarkword.core.deployment.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteId;
import org.quarkword.core.SiteRequest;
import org.quarkword.core.SiteResolver;
import org.quarkword.core.deployment.Requests;
import org.quarkword.core.runtime.Binding;
import org.quarkword.core.runtime.SiteBinding;

class ResolveOnceTest {

    static final AtomicInteger CALLS = new AtomicInteger();

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Requests.class, Counting.class, ResolveOnceTest.class));

    @ApplicationScoped
    public static class Counting implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            CALLS.incrementAndGet();
            return Optional.of(SiteId.of(5));
        }
    }

    @Inject
    SiteBinding binding;

    @Test
    void theResolversAreAskedOncePerRequest() {
        CALLS.set(0);
        Requests.fresh(() -> {
            assertThat(binding.current().site()).isEqualTo(SiteId.of(5));
            assertThat(binding.current().site()).isEqualTo(SiteId.of(5));
        });
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void readingTheBoundBindingNeverResolvesIt() {
        CALLS.set(0);
        Requests.fresh(() -> {
            assertThat(binding.bound()).isEmpty();
            assertThat(binding.bound()).isEmpty();
            binding.bind(Binding.site(SiteId.of(9)));
            assertThat(binding.bound()).contains(Binding.site(SiteId.of(9)));
        });
        assertThat(CALLS.get()).isZero();
    }
}

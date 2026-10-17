package org.quarkpress.core.deployment.resolution;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.core.runtime.SiteResolution;

class SiteResolutionTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar ->
                    jar.addClasses(Requests.class, Low.class, High.class, Middle.class, SiteResolutionTest.class));

    @ApplicationScoped
    @Priority(10)
    public static class Low implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            return Optional.of(SiteId.of(3));
        }
    }

    @ApplicationScoped
    @Priority(200)
    public static class High implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            return request.host().equals("a.test") ? Optional.of(SiteId.of(7)) : Optional.empty();
        }
    }

    @ApplicationScoped
    @Priority(100)
    public static class Middle implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            return request.host().equals("b.test") ? Optional.of(SiteId.of(8)) : Optional.empty();
        }
    }

    @Inject
    SiteResolution resolution;

    @Inject
    SiteBinding binding;

    @Test
    void higherPriorityAsksFirstAndTheFirstAnswerWins() {
        assertThat(resolution.resolve(new SiteRequest("https", "a.test", 443, "/")))
                .contains(SiteId.of(7));
        assertThat(resolution.resolve(new SiteRequest("https", "b.test", 443, "/")))
                .contains(SiteId.of(8));
        assertThat(resolution.resolve(new SiteRequest("https", "c.test", 443, "/")))
                .contains(SiteId.of(3));
    }

    @Test
    void anUnboundRequestAsksTheResolversWithNone() {
        Requests.fresh(() -> assertThat(binding.current()).isEqualTo(Binding.site(SiteId.of(3))));
    }

    @Test
    void anExplicitBindingWins() {
        Requests.fresh(() -> {
            binding.bind(Binding.site(SiteId.of(9)));
            assertThat(binding.current().site()).isEqualTo(SiteId.of(9));
        });
        Requests.fresh(() -> {
            binding.bind(Binding.NETWORK);
            assertThat(binding.current()).isEqualTo(Binding.NETWORK);
        });
    }

    @Test
    void aBindingIsWrittenOnce() {
        Requests.fresh(() -> {
            binding.bind(Binding.site(SiteId.of(9)));
            binding.bind(Binding.site(SiteId.of(9)));
            assertThatThrownBy(() -> binding.bind(Binding.NETWORK)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> binding.bind(Binding.site(SiteId.of(4))))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(binding.current().site()).isEqualTo(SiteId.of(9));
        });
    }

    @Test
    void aReadFixesTheBinding() {
        Requests.fresh(() -> {
            assertThat(binding.current().site()).isEqualTo(SiteId.of(3));
            assertThatThrownBy(() -> binding.bind(Binding.NETWORK)).isInstanceOf(IllegalStateException.class);
        });
    }
}

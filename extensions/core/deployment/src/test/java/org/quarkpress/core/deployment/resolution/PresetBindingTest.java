package org.quarkpress.core.deployment.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteNotResolvedException;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;
import org.quarkpress.core.deployment.Requests;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.multisite.runtime.TestPresetSite;

class PresetBindingTest {

    static final AtomicInteger RESOLVER_CALLS = new AtomicInteger();
    static final AtomicReference<Binding> PRESET = TestPresetSite.PRESET;
    static final AtomicReference<URI> REDIRECT = TestPresetSite.REDIRECT;

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar ->
                    jar.addClasses(Requests.class, Counting.class, TestPresetSite.class, PresetBindingTest.class));

    @ApplicationScoped
    public static class Counting implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            RESOLVER_CALLS.incrementAndGet();
            return Optional.of(SiteId.of(5));
        }
    }

    @Inject
    SiteBinding binding;

    @Inject
    SiteContext context;

    @BeforeEach
    void reset() {
        RESOLVER_CALLS.set(0);
        PRESET.set(null);
        REDIRECT.set(null);
        TestPresetSite.ON_ASK.set(null);
    }

    @Test
    void aPresetBindingIsAdoptedWithoutAskingTheResolvers() {
        PRESET.set(Binding.site(SiteId.of(7)));
        Requests.fresh(() -> {
            assertThat(binding.current()).isEqualTo(Binding.site(SiteId.of(7)));
            assertThat(context.siteId()).isEqualTo(SiteId.of(7));
        });
        assertThat(RESOLVER_CALLS.get()).isZero();
    }

    @Test
    void boundAdoptsThePreset() {
        PRESET.set(Binding.site(SiteId.of(7)));
        Requests.fresh(() -> {
            assertThat(binding.bound()).contains(Binding.site(SiteId.of(7)));
            PRESET.set(Binding.site(SiteId.of(8)));
            assertThat(binding.current()).isEqualTo(Binding.site(SiteId.of(7)));
        });
        assertThat(RESOLVER_CALLS.get()).isZero();
    }

    @Test
    void anUnresolvedPresetStaysUnresolved() {
        PRESET.set(Binding.UNRESOLVED);
        Requests.fresh(() -> {
            assertThat(context.isResolved()).isFalse();
            assertThat(binding.current()).isEqualTo(Binding.UNRESOLVED);
        });
        assertThat(RESOLVER_CALLS.get()).isZero();
    }

    @Test
    void bindingAnotherSiteAfterAPresetFails() {
        PRESET.set(Binding.site(SiteId.of(7)));
        Requests.fresh(() -> {
            binding.bind(Binding.site(SiteId.of(7)));
            assertThatThrownBy(() -> binding.bind(Binding.site(SiteId.of(9))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("the request is already bound to " + Binding.site(SiteId.of(7)));
        });
    }

    @Test
    void withoutAPresetTheResolversAnswerOnce() {
        Requests.fresh(() -> {
            assertThat(binding.bound()).isEmpty();
            assertThat(binding.current()).isEqualTo(Binding.site(SiteId.of(5)));
            assertThat(binding.current()).isEqualTo(Binding.site(SiteId.of(5)));
        });
        assertThat(RESOLVER_CALLS.get()).isEqualTo(1);
    }

    @Test
    void theRedirectFollowsThePresetBinding() {
        PRESET.set(Binding.site(SiteId.of(7)));
        REDIRECT.set(URI.create("https://primary.example/"));
        Requests.fresh(() -> assertThat(context.redirect()).contains(URI.create("https://primary.example/")));
        PRESET.set(null);
        Requests.fresh(() -> assertThat(context.redirect()).isEmpty());
    }

    @Test
    void aNetworkPresetIsRefused() {
        PRESET.set(Binding.NETWORK);
        Requests.fresh(() -> {
            assertThatThrownBy(() -> binding.bound())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("an HTTP layer cannot bind a request to the network");
            assertThatThrownBy(() -> binding.current())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("an HTTP layer cannot bind a request to the network");
        });
        assertThat(RESOLVER_CALLS.get()).isZero();
    }

    @Test
    void anUnresolvedPresetRefusesALaterBind() {
        PRESET.set(Binding.UNRESOLVED);
        Requests.fresh(() -> assertThatThrownBy(() -> binding.bind(Binding.site(SiteId.of(7))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the request is already bound to " + Binding.UNRESOLVED));
    }

    @Test
    void aPresetThatReadsTheCurrentSiteFailsCleanly() {
        PRESET.set(Binding.site(SiteId.of(7)));
        TestPresetSite.ON_ASK.set(() -> context.isResolved());
        Requests.fresh(() -> assertThatThrownBy(() -> binding.current())
                .isInstanceOf(SiteNotResolvedException.class)
                .hasMessage("a PresetSite must not read the current site"));
    }

    @Test
    void theRedirectDoesNotRunTheResolvers() {
        Requests.fresh(() -> assertThat(context.redirect()).isEmpty());
        assertThat(RESOLVER_CALLS.get()).isZero();
    }

    @Test
    void onlyAScopeMayBindItsOwnContext() {
        Requests.fresh(() -> {
            for (Binding target : new Binding[] {Binding.site(SiteId.of(7)), Binding.NETWORK}) {
                assertThatThrownBy(() -> binding.bindScope(target))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("only a scope binds its own request context");
            }
            assertThat(binding.bound()).isEmpty();
        });
    }
}

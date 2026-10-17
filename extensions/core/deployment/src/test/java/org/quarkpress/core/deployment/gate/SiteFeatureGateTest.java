package org.quarkpress.core.deployment.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.ContentFilter;
import org.quarkpress.core.RenderContext;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.SiteFeatureDisabledException;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.deployment.Requests;
import org.quarkpress.core.deployment.features.TestFeatures;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;

class SiteFeatureGateTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    Requests.class,
                    TestFeatures.class,
                    MutableSettings.class,
                    SiteOne.class,
                    DemoEvent.class,
                    GatedObserver.class,
                    GatedFilter.class,
                    BaseService.class,
                    ServiceDefaults.class,
                    GatedService.class,
                    Greeter.class,
                    GreeterBean.class,
                    SiteFeatureGateTest.class))
            .addBuildChainCustomizer(chain -> TestFeatures.declare(
                    chain,
                    new SiteFeatureBuildItem("demo", "test", false, "Demo"),
                    new SiteFeatureBuildItem("extra", "test", false, "Extra")));

    public record DemoEvent(String text) {}

    @ApplicationScoped
    public static class GatedObserver {
        private final List<String> seen = new CopyOnWriteArrayList<>();

        @SiteFeature("demo")
        void on(@Observes DemoEvent event) {
            seen.add(event.text());
        }

        @SiteFeature("demo")
        void onAsync(@ObservesAsync DemoEvent event) {
            seen.add("async:" + event.text());
        }

        public List<String> seen() {
            return seen;
        }

        public void clear() {
            seen.clear();
        }
    }

    @ApplicationScoped
    public static class GatedFilter implements ContentFilter {
        @SiteFeature("demo")
        @Override
        public String apply(String html, RenderContext ctx) {
            return html + "+demo";
        }

        public String describe() {
            return "plain";
        }
    }

    public static class BaseService {
        public String inherited() {
            return "inherited";
        }
    }

    public interface ServiceDefaults {
        default String fromInterface() {
            return "default";
        }
    }

    @ApplicationScoped
    @SiteFeature("demo")
    public static class GatedService extends BaseService implements ServiceDefaults {
        public String hello() {
            return "hello";
        }

        @SiteFeature("extra")
        public String extra() {
            return "extra";
        }

        @Override
        public String toString() {
            return "service";
        }
    }

    public interface Greeter {
        @SiteFeature("demo")
        default String greet() {
            return "hi";
        }
    }

    @ApplicationScoped
    public static class GreeterBean implements Greeter {}

    private static final RenderContext CTX = () -> SiteId.of(1);

    @Inject
    MutableSettings settings;

    @Inject
    Event<DemoEvent> events;

    @Inject
    GatedObserver observer;

    @Inject
    GatedFilter filter;

    @Inject
    GatedService service;

    @Inject
    GreeterBean greeter;

    @Inject
    SiteBinding binding;

    @BeforeEach
    void reset() {
        settings.clear();
        observer.clear();
    }

    @Test
    void aDisabledFeatureSkipsObserversPassesFiltersAndRefusesServices() {
        Requests.fresh(() -> {
            events.fire(new DemoEvent("x"));
            assertThat(observer.seen()).isEmpty();
            assertThat(filter.apply("html", CTX)).isEqualTo("html");
            assertThat(filter.describe()).isEqualTo("plain");
            assertThatThrownBy(() -> service.hello())
                    .isInstanceOf(SiteFeatureDisabledException.class)
                    .satisfies(e ->
                            assertThat(((SiteFeatureDisabledException) e).key()).isEqualTo("demo"));
        });
    }

    @Test
    void anEnabledFeatureRunsEverything() {
        settings.set("demo", true);
        Requests.fresh(() -> {
            events.fire(new DemoEvent("x"));
            assertThat(observer.seen()).containsExactly("x");
            assertThat(filter.apply("html", CTX)).isEqualTo("html+demo");
            assertThat(service.hello()).isEqualTo("hello");
            assertThat(service.inherited()).isEqualTo("inherited");
            assertThat(service.fromInterface()).isEqualTo("default");
            assertThat(greeter.greet()).isEqualTo("hi");
        });
    }

    @Test
    void aClassLevelGateCoversInheritedAndDefaultMethodsButNotObjectMethods() {
        Requests.fresh(() -> {
            assertThatThrownBy(() -> service.inherited())
                    .isInstanceOf(SiteFeatureDisabledException.class)
                    .hasMessage("QuarkPress feature 'demo' is disabled for the current site");
            assertThatThrownBy(() -> service.fromInterface())
                    .isInstanceOf(SiteFeatureDisabledException.class)
                    .hasMessage("QuarkPress feature 'demo' is disabled for the current site");
            assertThat(service.toString()).isEqualTo("service");
        });
    }

    @Test
    void aMethodAnnotationWinsOverTheClassAnnotation() {
        settings.set("demo", true);
        Requests.fresh(() -> {
            assertThat(service.hello()).isEqualTo("hello");
            assertThatThrownBy(() -> service.extra())
                    .isInstanceOf(SiteFeatureDisabledException.class)
                    .satisfies(e ->
                            assertThat(((SiteFeatureDisabledException) e).key()).isEqualTo("extra"));
        });
        settings.set("demo", false);
        settings.set("extra", true);
        Requests.fresh(() -> assertThat(service.extra()).isEqualTo("extra"));
    }

    @Test
    void aGatedDefaultMethodIsGated() {
        Requests.fresh(() -> assertThatThrownBy(() -> greeter.greet())
                .isInstanceOf(SiteFeatureDisabledException.class)
                .hasMessage("QuarkPress feature 'demo' is disabled for the current site"));
    }

    @Test
    void asyncObserversAreSkippedWhenDisabledAndRunWhenEnabled() throws Exception {
        Requests.fresh(() -> join(events.fireAsync(new DemoEvent("a"))));
        assertThat(observer.seen()).isEmpty();
        settings.set("demo", true);
        Requests.fresh(() -> join(events.fireAsync(new DemoEvent("b"))));
        assertThat(observer.seen()).containsExactly("async:b");
    }

    @Test
    void aNetworkBindingCountsAsDisabled() {
        settings.set("demo", true);
        Requests.fresh(() -> {
            binding.bind(Binding.NETWORK);
            events.fire(new DemoEvent("x"));
            assertThat(observer.seen()).isEmpty();
            assertThat(filter.apply("html", CTX)).isEqualTo("html");
            assertThatThrownBy(() -> service.hello())
                    .isInstanceOf(SiteFeatureDisabledException.class)
                    .hasMessage("QuarkPress feature 'demo' is disabled for the current site");
        });
    }

    @Test
    void anUnresolvedSiteCountsAsDisabled() {
        settings.set("demo", true);
        Requests.fresh(() -> {
            binding.bind(Binding.UNRESOLVED);
            assertThatThrownBy(() -> service.hello()).isInstanceOf(SiteFeatureDisabledException.class);
        });
    }

    @Test
    void codeWithoutARequestContextCountsAsDisabled() {
        settings.set("demo", true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Requests.outside(() -> {
            try {
                service.hello();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        assertThat(failure.get()).isInstanceOf(SiteFeatureDisabledException.class);
    }

    private static void join(java.util.concurrent.CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

package org.quarkpress.core.deployment.gate;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.FeatureSettings;
import org.quarkpress.core.SiteEvent;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.deployment.Requests;
import org.quarkpress.core.deployment.features.TestFeatures;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

class SiteEventGateTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    Requests.class,
                    TestFeatures.class,
                    SiteOne.class,
                    PerSiteSettings.class,
                    Happened.class,
                    MethodGated.class,
                    ClassGated.class,
                    Shaped.class,
                    SiteEventGateTest.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")));

    public record Happened(SiteId site, Instant at, String text) implements SiteEvent {}

    @ApplicationScoped
    public static class PerSiteSettings implements FeatureSettings {
        private final Map<SiteId, Map<String, Boolean>> bySite = new ConcurrentHashMap<>();

        @Override
        public Map<String, Boolean> siteValues(SiteId site) {
            return bySite.getOrDefault(site, Map.of());
        }

        @Override
        public Map<String, Boolean> networkDefaults() {
            return Map.of();
        }

        void enable(SiteId site) {
            bySite.put(site, Map.of("demo", true));
        }

        void clear() {
            bySite.clear();
        }
    }

    @ApplicationScoped
    public static class MethodGated {
        static final List<String> seen = new CopyOnWriteArrayList<>();

        @SiteFeature("demo")
        void on(@Observes Happened event) {
            seen.add("sync:" + event.text());
        }

        @SiteFeature("demo")
        void onAsync(@ObservesAsync Happened event) {
            seen.add("async:" + event.text());
        }
    }

    @ApplicationScoped
    @SiteFeature("demo")
    public static class ClassGated {
        static final List<String> seen = new CopyOnWriteArrayList<>();

        void on(@Observes Happened event) {
            seen.add("class:" + event.text());
        }

        void onAsync(@ObservesAsync Happened event) {
            seen.add("classAsync:" + event.text());
        }
    }

    @ApplicationScoped
    public static class Shaped {
        static final List<String> seen = new CopyOnWriteArrayList<>();

        @SiteFeature("demo")
        void later(PerSiteSettings injected, @Observes Happened event) {
            seen.add("later:" + event.text());
        }

        @SiteFeature("demo")
        void wide(@Observes SiteEvent event) {
            seen.add("wide:" + event.site().value());
        }
    }

    private static final Instant AT = Instant.parse("2026-10-17T12:00:00Z");

    @Inject
    PerSiteSettings settings;

    @Inject
    Event<Happened> events;

    @BeforeEach
    void reset() {
        settings.clear();
        MethodGated.seen.clear();
        ClassGated.seen.clear();
        Shaped.seen.clear();
    }

    @Test
    void aSyncObserverFollowsTheEventsSiteNotTheRequestsSite() {
        settings.enable(SiteId.of(2));
        Requests.fresh(() -> {
            events.fire(new Happened(SiteId.of(2), AT, "two"));
            events.fire(new Happened(SiteId.of(1), AT, "one"));
        });
        assertThat(MethodGated.seen).containsExactly("sync:two");
        assertThat(ClassGated.seen).containsExactly("class:two");
    }

    @Test
    void anAsyncObserverFollowsTheEventsSite() throws Exception {
        settings.enable(SiteId.of(2));
        events.fireAsync(new Happened(SiteId.of(2), AT, "two"))
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        events.fireAsync(new Happened(SiteId.of(1), AT, "one"))
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertThat(MethodGated.seen).containsExactly("async:two");
    }

    @Test
    void anObserverWithoutARequestContextFollowsTheEventsSite() {
        settings.enable(SiteId.of(3));
        Requests.outside(() -> events.fire(new Happened(SiteId.of(3), AT, "three")));
        assertThat(MethodGated.seen).containsExactly("sync:three");
        assertThat(ClassGated.seen).containsExactly("class:three");
    }

    @Test
    void anObservedParameterThatIsNotFirstAndASupertypeObserverFollowTheEventsSite() {
        settings.enable(SiteId.of(2));
        Requests.fresh(() -> {
            events.fire(new Happened(SiteId.of(2), AT, "two"));
            events.fire(new Happened(SiteId.of(1), AT, "one"));
        });
        assertThat(Shaped.seen).containsExactlyInAnyOrder("later:two", "wide:2");
    }

    @Test
    void aClassLevelAsyncObserverFollowsTheEventsSite() throws Exception {
        settings.enable(SiteId.of(2));
        events.fireAsync(new Happened(SiteId.of(2), AT, "two"))
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        events.fireAsync(new Happened(SiteId.of(1), AT, "one"))
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        assertThat(ClassGated.seen).containsExactly("classAsync:two");
    }
}

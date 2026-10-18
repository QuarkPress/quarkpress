package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.NetworkOptionChanged;
import org.quarkpress.core.OptionChanged;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteCreated;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.SiteFeatureToggled;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.core.runtime.FeatureResolver;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.FeatureOptions;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.scheduling.runtime.ScopeProbe;

class AsyncEventsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(
                    AsyncEventsDbTest.class, Heard.class, GatedCreated.class, ScopeProbe.class)
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("alpha", "test", false, "Alpha")));

    @ApplicationScoped
    public static class Heard {
        final BlockingQueue<OptionChanged> options = new LinkedBlockingQueue<>();
        final BlockingQueue<SiteFeatureToggled> toggles = new LinkedBlockingQueue<>();

        @Inject
        OptionStore store;

        @Inject
        SiteScope scope;

        final Map<String, Object> committedView = new ConcurrentHashMap<>();
        final BlockingQueue<NetworkOptionChanged> networkOptions = new LinkedBlockingQueue<>();

        void on(@ObservesAsync OptionChanged event) throws Exception {
            // read in a fresh transaction on another connection: only a committed change is visible
            committedView.put(
                    event.name(),
                    scope.call(
                            event.site(),
                            () -> store.find(event.site(), event.name())
                                    .<Object>map(v -> v)
                                    .orElse("absent")));
            options.add(event);
        }

        void on(@ObservesAsync NetworkOptionChanged event) {
            networkOptions.add(event);
        }

        /** Fails the write of an option whose name starts with "refuse-", in the writer's transaction. */
        void refuse(@Observes OptionChanged event) {
            if (event.name().startsWith("refuse-")) {
                throw new IllegalStateException("observer refused");
            }
        }

        public Object committedView(String name) {
            return committedView.get(name);
        }

        public NetworkOptionChanged pollNetwork(long millis) throws InterruptedException {
            return networkOptions.poll(millis, TimeUnit.MILLISECONDS);
        }

        void on(@ObservesAsync SiteFeatureToggled event) {
            toggles.add(event);
        }

        /** Through methods, never fields: the injected reference is a client proxy with fields of its own. */
        public OptionChanged pollOption(long millis) throws InterruptedException {
            return options.poll(millis, TimeUnit.MILLISECONDS);
        }

        public SiteFeatureToggled pollToggle(long millis) throws InterruptedException {
            return toggles.poll(millis, TimeUnit.MILLISECONDS);
        }
    }

    /** A synchronous observer of a site's creation that runs only where the site has the feature. */
    @ApplicationScoped
    public static class GatedCreated {
        private final List<SiteId> seen = new CopyOnWriteArrayList<>();

        @SiteFeature("alpha")
        void on(@Observes SiteCreated event) {
            seen.add(event.site());
        }

        public List<SiteId> seen() {
            return List.copyOf(seen);
        }
    }

    @Inject
    SiteScope scope;

    @Inject
    Options options;

    @Inject
    FeatureToggles toggles;

    @Inject
    TestSites sites;

    @Inject
    Heard heard;

    @Inject
    GatedCreated gated;

    @Inject
    Event<SiteCreated> created;

    @Inject
    NetworkOptions networkOptions;

    @Inject
    ScopeProbe probe;

    @Inject
    OptionRepository repository;

    @Inject
    FeatureResolver features;

    private OptionChanged nextNamed(String name, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            OptionChanged event = heard.pollOption(100);
            if (event != null && event.name().equals(name)) {
                return event;
            }
        }
        return null;
    }

    @Test
    void asyncObserversHearACommittedChange() throws Exception {
        SiteId site = sites.create("async");
        String name = "async-" + UUID.randomUUID();
        scope.run(site, () -> options.set(name, TextNode.valueOf("v")));
        OptionChanged event = nextNamed(name, 10);
        assertThat(event).isNotNull();
        assertThat(event.site()).isEqualTo(site);
        assertThat(event.after()).isEqualTo(TextNode.valueOf("v"));
    }

    @Test
    void asyncObserversSeeTheChangeCommittedFromAFreshTransaction() throws Exception {
        SiteId site = sites.create("fresh");
        String name = "fresh-" + UUID.randomUUID();
        scope.run(site, () -> options.set(name, TextNode.valueOf("v")));
        assertThat(nextNamed(name, 10)).isNotNull();
        assertThat(heard.committedView(name)).isEqualTo(TextNode.valueOf("v"));
    }

    @Test
    void aFailingSynchronousObserverRollsBackAndNoAsyncEventIsDelivered() throws Exception {
        SiteId site = sites.create("refused");
        String name = "refuse-" + UUID.randomUUID();
        assertThatThrownBy(() -> scope.run(site, () -> options.set(name, TextNode.valueOf("v"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("observer refused");
        assertThat(nextNamed(name, 2)).isNull();
        assertThat(heard.committedView(name)).isNull();
    }

    @Test
    void networkOptionChangesReachAsyncObserversAfterCommit() throws Exception {
        String name = "net-" + UUID.randomUUID();
        probe.inNetwork(() -> networkOptions.set(name, TextNode.valueOf("n")));
        NetworkOptionChanged event = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (event == null && System.nanoTime() < deadline) {
            NetworkOptionChanged next = heard.pollNetwork(100);
            if (next != null && next.name().equals(name)) {
                event = next;
            }
        }
        assertThat(event).isNotNull();
        assertThat(event.after()).isEqualTo(TextNode.valueOf("n"));
    }

    @Test
    void asyncObserversDoNotHearARolledBackChange() throws Exception {
        SiteId site = sites.create("rollback");
        String name = "gone-" + UUID.randomUUID();
        assertThatThrownBy(() -> scope.run(site, () -> {
                    options.set(name, TextNode.valueOf("v"));
                    throw new IllegalStateException("roll back");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("roll back");
        assertThat(nextNamed(name, 2)).isNull();
    }

    @Test
    void asyncObserversHearToggles() throws Exception {
        SiteId site = sites.create("toggle");
        scope.run(site, () -> toggles.setForSite("alpha", true));
        SiteFeatureToggled event = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (event == null && System.nanoTime() < deadline) {
            SiteFeatureToggled next = heard.pollToggle(100);
            if (next != null && next.site().equals(site)) {
                event = next;
            }
        }
        assertThat(event).isNotNull();
        assertThat(event.enabled()).isTrue();
    }

    @Test
    void theNetworkScopeReadsSwitchesItHasNotCommittedYet() throws Exception {
        SiteId site = sites.create("network-read");
        ObjectNode switches = JsonNodeFactory.instance.objectNode().put("alpha", true);
        boolean enabled = probe.inNetwork(() -> {
            repository.persist(OptionEntity.create(
                    site, FeatureOptions.SITE, switches, true, Instant.parse("2026-10-17T12:00:00Z")));
            repository.flush();
            return features.forSite(site).isEnabled("alpha");
        });
        assertThat(enabled).isTrue();
    }

    @Test
    void aGatedObserverOfASiteCreationSeesTheSwitchesWrittenInTheSameTransaction() throws Exception {
        SiteId withSwitch = sites.create("created-on");
        SiteId without = sites.create("created-off");
        ObjectNode switches = JsonNodeFactory.instance.objectNode().put("alpha", true);
        probe.inNetwork(() -> {
            repository.persist(OptionEntity.create(
                    withSwitch, FeatureOptions.SITE, switches, true, Instant.parse("2026-10-17T12:00:00Z")));
            repository.flush();
            created.fire(creation(withSwitch));
            created.fire(creation(without));
            return null;
        });
        assertThat(gated.seen()).contains(withSwitch).doesNotContain(without);
    }

    private static SiteCreated creation(SiteId site) {
        return new SiteCreated(
                site,
                Instant.parse("2026-10-17T12:00:00Z"),
                new SiteInfo(site, "s" + site.value() + ".test", "/", "Site", 1, false, false, false, false));
    }
}

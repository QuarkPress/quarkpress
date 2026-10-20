package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.FeatureValue;
import org.quarkpress.core.FeatureValue.Source;
import org.quarkpress.core.OptionChanged;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteFeatureToggled;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.FeatureOptions;
import org.quarkpress.persistence.runtime.OptionFeatureSettings;
import org.quarkpress.persistence.runtime.OptionWrites;

class FeatureTogglesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(FeatureTogglesDbTest.class, ToggleLog.class)
            .addBuildChainCustomizer(chain -> TestFeatures.declare(
                    chain,
                    new SiteFeatureBuildItem("demo-off", "test", false, "Off by default"),
                    new SiteFeatureBuildItem("demo-on", "test", true, "On by default"),
                    new SiteFeatureBuildItem("demo-third", "test", false, "Off by default")));

    @ApplicationScoped
    public static class ToggleLog {
        private final List<SiteFeatureToggled> events = new CopyOnWriteArrayList<>();

        private final Set<SiteId> failing = ConcurrentHashMap.newKeySet();

        private final Set<SiteId> refusing = ConcurrentHashMap.newKeySet();

        private final Set<SiteId> slow = ConcurrentHashMap.newKeySet();

        void on(@Observes SiteFeatureToggled event) {
            events.add(event);
            if (failing.contains(event.site())) {
                throw new IllegalStateException("observer failed");
            }
            if (refusing.contains(event.site())) {
                throw new IllegalArgumentException("observer refused");
            }
        }

        /** Holds a write of the switches open, so a concurrent change would read the old switches without a lock. */
        void onOption(@Observes OptionChanged event) throws InterruptedException {
            if (slow.contains(event.site()) && event.name().equals(FeatureOptions.SITE)) {
                Thread.sleep(300);
            }
        }

        public void failFor(SiteId site) {
            failing.add(site);
        }

        public void refuseFor(SiteId site) {
            refusing.add(site);
        }

        public void slowFor(SiteId site) {
            slow.add(site);
        }

        public List<SiteFeatureToggled> of(SiteId site) {
            return events.stream().filter(e -> e.site().equals(site)).toList();
        }
    }

    @Inject
    FeatureToggles toggles;

    @Inject
    SiteScope scope;

    @Inject
    SiteContext context;

    @Inject
    TestSites sites;

    @Inject
    ToggleLog log;

    @Inject
    OptionFeatureSettings settings;

    @Inject
    OptionWrites writes;

    @Inject
    SiteBinding binding;

    @Inject
    UserTransaction userTransaction;

    /** The feature's value for {@code site}, read in a new request so no per-request cache is reused. */
    private FeatureValue valueIn(SiteId site, String key) {
        AtomicReference<FeatureValue> value = new AtomicReference<>();
        Requests.fresh(() -> scope.run(site, () -> value.set(context.features().value(key))));
        return value.get();
    }

    @Test
    void siteValuesBeatNetworkDefaultsWhichBeatModuleDefaults() {
        SiteId a = sites.create("toggle-a");
        SiteId b = sites.create("toggle-b");
        try {
            assertThat(valueIn(a, "demo-off")).isEqualTo(new FeatureValue("demo-off", false, Source.MODULE));
            Requests.fresh(() -> toggles.setNetworkDefault("demo-off", true));
            assertThat(valueIn(a, "demo-off")).isEqualTo(new FeatureValue("demo-off", true, Source.NETWORK));
            scope.run(a, () -> toggles.setForSite("demo-off", false));
            assertThat(valueIn(a, "demo-off")).isEqualTo(new FeatureValue("demo-off", false, Source.SITE));
            assertThat(valueIn(b, "demo-off")).isEqualTo(new FeatureValue("demo-off", true, Source.NETWORK));
            scope.run(a, () -> toggles.clearForSite("demo-off"));
            assertThat(valueIn(a, "demo-off")).isEqualTo(new FeatureValue("demo-off", true, Source.NETWORK));
        } finally {
            Requests.fresh(() -> toggles.clearNetworkDefault("demo-off"));
        }
        assertThat(valueIn(a, "demo-off")).isEqualTo(new FeatureValue("demo-off", false, Source.MODULE));
    }

    @Test
    void anEventIsFiredOnlyWhenTheEffectiveValueChanges() {
        SiteId a = sites.create("events");
        scope.run(a, () -> {
            toggles.setForSite("demo-on", true);
            toggles.setForSite("demo-on", false);
            toggles.clearForSite("demo-on");
        });
        assertThat(log.of(a)).extracting(SiteFeatureToggled::enabled).containsExactly(false, true);
    }

    @Test
    void theChangeIsVisibleInTheSameRequest() {
        SiteId a = sites.create("same-request");
        scope.run(a, () -> {
            assertThat(context.features().isEnabled("demo-off")).isFalse();
            toggles.setForSite("demo-off", true);
            assertThat(context.features().isEnabled("demo-off")).isTrue();
        });
    }

    @Test
    void unknownKeysAreRejected() {
        SiteId a = sites.create("unknown");
        assertThatThrownBy(() -> scope.run(a, () -> toggles.setForSite("nope", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void settingsReadTheGivenSiteNotTheBoundOne() {
        SiteId a = sites.create("given-a");
        SiteId b = sites.create("given-b");
        scope.run(a, () -> toggles.setForSite("demo-off", true));
        scope.run(b, () -> {
            assertThat(settings.siteValues(a)).containsEntry("demo-off", true);
            assertThat(settings.siteValues(b)).doesNotContainKey("demo-off");
        });
        Requests.fresh(() -> assertThat(settings.siteValues(a)).containsEntry("demo-off", true));
    }

    @Test
    void aRolledBackToggleLeavesNoStaleCachedValue() {
        SiteId a = sites.create("rollback");
        scope.run(a, () -> {
            assertThat(context.features().isEnabled("demo-off")).isFalse();
            assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
                        toggles.setForSite("demo-off", true);
                        assertThat(context.features().isEnabled("demo-off")).isTrue();
                        QuarkusTransaction.setRollbackOnly();
                    }))
                    .isInstanceOf(io.quarkus.narayana.jta.QuarkusTransactionException.class);
            assertThat(context.features().isEnabled("demo-off")).isFalse();
        });
        assertThat(valueIn(a, "demo-off").enabled()).isFalse();
    }

    @Test
    void anObserverFailureRollsTheToggleBack() {
        SiteId a = sites.create("observer");
        log.failFor(a);
        assertThatThrownBy(() -> scope.run(a, () -> toggles.setForSite("demo-on", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("observer failed");
        assertThat(valueIn(a, "demo-on").enabled()).isTrue();
    }

    @Test
    void anUnknownKeyDoesNotMarkTheCallersTransactionForRollback() {
        SiteId a = sites.create("unknown-tx");
        scope.run(
                a,
                () -> QuarkusTransaction.requiringNew().run(() -> {
                    assertThatThrownBy(() -> toggles.setForSite("nope", true))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThat(QuarkusTransaction.getStatus()).isEqualTo(jakarta.transaction.Status.STATUS_ACTIVE);
                }));
    }

    @Test
    void anIllegalArgumentFromAnObserverRollsTheToggleBack() {
        SiteId a = sites.create("observer-iae");
        log.refuseFor(a);
        Requests.fresh(() -> {
            binding.bind(Binding.site(a));
            assertThatThrownBy(() -> toggles.setForSite("demo-on", false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("observer refused");
        });
        assertThat(valueIn(a, "demo-on").enabled()).isTrue();
        assertThatThrownBy(() -> scope.run(
                        a,
                        () -> QuarkusTransaction.requiringNew()
                                .run(() -> assertThatThrownBy(() -> toggles.setForSite("demo-on", false))
                                        .isInstanceOf(IllegalArgumentException.class)
                                        .hasMessage("observer refused"))))
                .satisfies(failure -> assertThat(Failures.messages(failure)).containsIgnoringCase("rollback"));
        Requests.fresh(() -> assertThat(settings.siteValues(a)).isEmpty());
    }

    @Test
    void concurrentTogglesOfDifferentKeysBothSurvive() throws Exception {
        SiteId withoutRow = sites.create("concurrent-new");
        SiteId withRow = sites.create("concurrent-existing");
        scope.run(withRow, () -> toggles.setForSite("demo-third", true));
        for (SiteId site : List.of(withoutRow, withRow)) {
            log.slowFor(site);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(() -> {
                    start.await();
                    scope.run(site, () -> toggles.setForSite("demo-off", true));
                    return null;
                });
                Future<?> second = pool.submit(() -> {
                    start.await();
                    scope.run(site, () -> toggles.setForSite("demo-on", false));
                    return null;
                });
                start.countDown();
                first.get(60, TimeUnit.SECONDS);
                second.get(60, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            Requests.fresh(() -> assertThat(settings.siteValues(site))
                    .containsEntry("demo-off", true)
                    .containsEntry("demo-on", false));
            assertThat(valueIn(site, "demo-off").enabled()).isTrue();
            assertThat(valueIn(site, "demo-on").enabled()).isFalse();
        }
        Requests.fresh(() -> assertThat(settings.siteValues(withRow)).containsEntry("demo-third", true));
    }

    @Test
    void theBeforeValueComesFromTheStoredSwitchesNotTheRequestCache() {
        SiteId a = sites.create("before");
        Requests.fresh(() -> {
            binding.bind(Binding.site(a));
            assertThat(context.features().isEnabled("demo-off")).isFalse();
            // Another request turns it on; this request's cached value is now stale.
            Requests.fresh(() -> scope.run(a, () -> toggles.setForSite("demo-off", true)));
            toggles.setForSite("demo-off", false);
        });
        assertThat(log.of(a))
                .extracting(SiteFeatureToggled::key, SiteFeatureToggled::enabled)
                .containsExactly(tuple("demo-off", true), tuple("demo-off", false));
    }

    @Test
    void storedValuesThatAreNotSwitchesAreIgnored() {
        SiteId text = sites.create("not-object");
        scope.run(text, () -> writes.set(text, FeatureOptions.SITE, TextNode.valueOf("on"), null));
        assertThat(valueIn(text, "demo-off")).isEqualTo(new FeatureValue("demo-off", false, Source.MODULE));
        scope.run(text, () -> toggles.setForSite("demo-off", true));
        assertThat(valueIn(text, "demo-off")).isEqualTo(new FeatureValue("demo-off", true, Source.SITE));
        assertThat(log.of(text)).extracting(SiteFeatureToggled::enabled).containsExactly(true);

        SiteId members = sites.create("not-boolean");
        ObjectNode stored =
                JsonNodeFactory.instance.objectNode().put("demo-on", "no").put("demo-off", 1);
        scope.run(members, () -> writes.set(members, FeatureOptions.SITE, stored, null));
        assertThat(valueIn(members, "demo-on")).isEqualTo(new FeatureValue("demo-on", true, Source.MODULE));
        assertThat(valueIn(members, "demo-off")).isEqualTo(new FeatureValue("demo-off", false, Source.MODULE));
        scope.run(members, () -> toggles.setForSite("demo-on", false));
        assertThat(log.of(members))
                .extracting(SiteFeatureToggled::key, SiteFeatureToggled::enabled)
                .containsExactly(tuple("demo-on", false));
        Requests.fresh(() -> assertThat(settings.siteValues(members)).containsExactly(entry("demo-on", false)));
    }

    @Test
    void aNetworkBoundCallerReadsASitesSwitches() {
        SiteId a = sites.create("network-reader");
        scope.run(a, () -> toggles.setForSite("demo-off", true));
        Requests.fresh(() -> {
            binding.bind(Binding.NETWORK);
            assertThat(settings.siteValues(a)).containsExactly(entry("demo-off", true));
        });
    }

    @Test
    void aRolledBackNetworkDefaultLeavesNoStaleCachedValue() {
        SiteId a = sites.create("network-rollback");
        Requests.fresh(() -> {
            binding.bind(Binding.site(a));
            assertThat(context.features().isEnabled("demo-third")).isFalse();
            try {
                userTransaction.begin();
                toggles.setNetworkDefault("demo-third", true);
                assertThat(context.features().isEnabled("demo-third")).isTrue();
                userTransaction.rollback();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            assertThat(context.features().isEnabled("demo-third")).isFalse();
        });
        assertThat(valueIn(a, "demo-third").enabled()).isFalse();
    }

    @Test
    void aCommittedNetworkDefaultDropsWhatAnotherThreadOfTheRequestCachedMeanwhile() {
        SiteId a = sites.create("network-commit");
        try {
            Requests.fresh(() -> {
                binding.bind(Binding.site(a));
                try {
                    userTransaction.begin();
                    toggles.setNetworkDefault("demo-third", true);
                    // Another thread of the request computes the features in a transaction of its own.
                    boolean seen =
                            Requests.onSharedContext(() -> context.features().isEnabled("demo-third"));
                    assertThat(seen).isFalse();
                    userTransaction.commit();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                assertThat(context.features().isEnabled("demo-third")).isTrue();
            });
        } finally {
            Requests.fresh(() -> toggles.clearNetworkDefault("demo-third"));
        }
    }
}

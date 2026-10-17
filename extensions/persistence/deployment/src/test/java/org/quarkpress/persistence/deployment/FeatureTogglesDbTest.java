package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.FeatureValue;
import org.quarkpress.core.FeatureValue.Source;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteFeatureToggled;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.OptionFeatureSettings;

class FeatureTogglesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(FeatureTogglesDbTest.class, ToggleLog.class)
            .addBuildChainCustomizer(chain -> TestFeatures.declare(
                    chain,
                    new SiteFeatureBuildItem("demo-off", "test", false, "Off by default"),
                    new SiteFeatureBuildItem("demo-on", "test", true, "On by default")));

    @ApplicationScoped
    public static class ToggleLog {
        private final List<SiteFeatureToggled> events = new CopyOnWriteArrayList<>();

        private final java.util.Set<SiteId> failing = java.util.concurrent.ConcurrentHashMap.newKeySet();

        void on(@Observes SiteFeatureToggled event) {
            events.add(event);
            if (failing.contains(event.site())) {
                throw new IllegalStateException("observer failed");
            }
        }

        public void failFor(SiteId site) {
            failing.add(site);
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
}

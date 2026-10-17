package org.quarkpress.core.deployment.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.FeatureValue;
import org.quarkpress.core.FeatureValue.Source;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.deployment.Requests;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.core.runtime.FeatureResolver;
import org.quarkpress.core.runtime.RequestFeatureCache;

class FeatureResolutionTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    Requests.class, TestFeatures.class, CountingSettings.class, FeatureResolutionTest.class))
            .addBuildChainCustomizer(chain -> TestFeatures.declare(
                    chain,
                    new SiteFeatureBuildItem("demo-off", "test", false, "Off by default"),
                    new SiteFeatureBuildItem("demo-on", "test", true, "On by default")));

    private static final SiteId SITE = SiteId.of(1);

    @Inject
    CountingSettings settings;

    @Inject
    FeatureResolver resolver;

    @Inject
    RequestFeatureCache cache;

    @BeforeEach
    void reset() {
        settings.reset();
    }

    @Test
    void moduleDefaultsApplyWhenNothingIsSet() {
        Requests.fresh(() -> {
            assertThat(resolver.forSite(SITE).value("demo-off"))
                    .isEqualTo(new FeatureValue("demo-off", false, Source.MODULE));
            assertThat(resolver.forSite(SITE).isEnabled("demo-on")).isTrue();
        });
    }

    @Test
    void networkDefaultsBeatModuleDefaultsAndSiteValuesBeatBoth() {
        settings.network().put("demo-off", true);
        Requests.fresh(() -> assertThat(resolver.forSite(SITE).value("demo-off"))
                .isEqualTo(new FeatureValue("demo-off", true, Source.NETWORK)));
        settings.site().put("demo-off", false);
        Requests.fresh(() -> assertThat(resolver.forSite(SITE).value("demo-off"))
                .isEqualTo(new FeatureValue("demo-off", false, Source.SITE)));
    }

    @Test
    void valuesAreReadOncePerRequestUntilInvalidated() {
        Requests.fresh(() -> {
            resolver.forSite(SITE).isEnabled("demo-on");
            resolver.forSite(SITE).isEnabled("demo-off");
            assertThat(settings.siteReads()).isEqualTo(1);
            cache.invalidate(SITE);
            resolver.forSite(SITE).isEnabled("demo-on");
            assertThat(settings.siteReads()).isEqualTo(2);
        });
    }

    @Test
    void withoutARequestContextNothingIsCached() {
        Requests.outside(() -> {
            resolver.forSite(SITE).isEnabled("demo-on");
            resolver.forSite(SITE).isEnabled("demo-on");
        });
        assertThat(settings.siteReads()).isEqualTo(2);
    }

    @Test
    void unknownKeysAreRejected() {
        Requests.run(() -> assertThatThrownBy(() -> resolver.forSite(SITE).isEnabled("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("demo-on"));
    }

    @Test
    void withoutASiteEverythingIsDisabled() {
        assertThat(resolver.noSite().all())
                .containsExactly(
                        new FeatureValue("demo-off", false, Source.NO_SITE),
                        new FeatureValue("demo-on", false, Source.NO_SITE));
    }

    @Test
    void threadsSharingARequestContextComputeOnce() throws Exception {
        int threads = 8;
        Requests.fresh(() -> {
            ManagedContext context = Arc.container().requestContext();
            var state = context.getState();
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<List<FeatureValue>>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    results.add(pool.submit(() -> {
                        context.activate(state);
                        try {
                            go.await();
                            return resolver.forSite(SITE).all();
                        } finally {
                            context.deactivate();
                        }
                    }));
                }
                go.countDown();
                List<FeatureValue> first = results.get(0).get();
                for (Future<List<FeatureValue>> result : results) {
                    assertThat(result.get()).isEqualTo(first);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                pool.shutdownNow();
            }
            assertThat(settings.siteReads()).isEqualTo(1);
        });
    }

    @Test
    void twoSitesInOneRequestAreResolvedSeparately() {
        SiteId other = SiteId.of(2);
        settings.site(SITE).put("demo-off", true);
        settings.site(other).put("demo-on", false);
        Requests.fresh(() -> {
            assertThat(resolver.forSite(SITE).isEnabled("demo-off")).isTrue();
            assertThat(resolver.forSite(SITE).isEnabled("demo-on")).isTrue();
            assertThat(resolver.forSite(other).isEnabled("demo-off")).isFalse();
            assertThat(resolver.forSite(other).isEnabled("demo-on")).isFalse();
            assertThat(resolver.forSite(other).value("demo-on").source()).isEqualTo(Source.SITE);
            assertThat(settings.siteReads(SITE)).isEqualTo(1);
            assertThat(settings.siteReads(other)).isEqualTo(1);
        });
    }

    @Test
    void invalidateAllDropsEverySite() {
        SiteId other = SiteId.of(2);
        Requests.fresh(() -> {
            resolver.forSite(SITE).all();
            resolver.forSite(other).all();
            assertThat(settings.siteReads()).isEqualTo(2);
            cache.invalidateAll();
            resolver.forSite(SITE).all();
            resolver.forSite(other).all();
            assertThat(settings.siteReads()).isEqualTo(4);
        });
    }

    @Test
    void allFollowsTheRegistryKeyOrder() {
        Requests.fresh(() -> assertThat(resolver.forSite(SITE).all())
                .extracting(FeatureValue::key)
                .containsExactly("demo-off", "demo-on"));
    }

    @Test
    void aMissingSiteIsRejected() {
        assertThatThrownBy(() -> resolver.forSite(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("site");
    }

    @Test
    void theUnresolvedSiteIdCannotBeBuilt() {
        assertThatThrownBy(() -> SiteId.of(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noSiteRejectsUnknownKeysInsideARequest() {
        Requests.run(() -> assertThatThrownBy(() -> resolver.noSite().isEnabled("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope"));
    }
}

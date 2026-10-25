package org.quarkpress.scheduling.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.FeatureDescriptor;
import org.quarkpress.core.FeatureRegistry;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.scheduling.SchedulingStatus;
import org.quarkpress.scheduling.runtime.Jobs;
import org.quarkpress.scheduling.runtime.SchedulingConfig;

class FeatureDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SchedulingApp.with(FeatureDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    FeatureRegistry features;

    @Inject
    SiteContext context;

    @Inject
    SchedulingConfig config;

    @Inject
    SchedulingStatus status;

    @Test
    void theModuleDeclaresTheSchedulingFeatureOnByDefault() throws Exception {
        FeatureDescriptor scheduling = features.find("scheduling").orElseThrow();
        assertThat(scheduling.module()).isEqualTo("scheduling");
        assertThat(scheduling.defaultEnabled()).isTrue();
        SiteId site = TestData.site(dataSource);
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> context.features().isEnabled("scheduling")))
                .isTrue();
        TestData.featureOff(dataSource, site, "scheduling");
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> context.features().isEnabled("scheduling")))
                .isFalse();
        TestData.featureOn(dataSource, site, "scheduling");
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> context.features().isEnabled("scheduling")))
                .isTrue();
    }

    @Test
    void theConfigurationHasItsDefaults() {
        assertThat(config.enabled()).isTrue();
        assertThat(config.interval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(config.batchSize()).isEqualTo(100);
        assertThat(config.housekeepingInterval()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void theStatusBeanIsInjectableAsThePublicInterface() {
        assertThat(status.snapshot().publishedSinceStart()).isZero();
    }

    @Test
    void aJobRunsOnAThreadOfItsOwnOutsideATransaction() {
        Thread caller = Thread.currentThread();
        assertThat(Jobs.detached(Thread::currentThread)).isNotSameAs(caller);
        assertThat(Jobs.detached(QuarkusTransaction::isActive)).isFalse();
    }

    @Test
    void aJobKeepsTheCallersContextClassLoader() {
        ClassLoader callers = Thread.currentThread().getContextClassLoader();
        assertThat(Jobs.detached(() -> Thread.currentThread().getContextClassLoader()))
                .isSameAs(callers);
    }

    @Test
    void aRuntimeExceptionOfTheJobIsRethrownUnchanged() {
        RuntimeException thrown = new IllegalArgumentException("bad");
        assertThatThrownBy(() -> Jobs.detached(() -> {
                    throw thrown;
                }))
                .isSameAs(thrown);
    }

    @Test
    void anErrorOfTheJobIsRethrownUnchanged() {
        Error thrown = new AssertionError("broken");
        assertThatThrownBy(() -> Jobs.detached(() -> {
                    throw thrown;
                }))
                .isSameAs(thrown);
    }

    @Test
    void aCheckedExceptionOfTheJobIsWrapped() {
        IOException thrown = new IOException("disk");
        assertThatThrownBy(() -> Jobs.detached(() -> {
                    throw thrown;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(thrown);
    }

    @Test
    void anInterruptedCallerKeepsItsInterruptionAndIsTold() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> Jobs.detached(() -> release.await(10, TimeUnit.SECONDS)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("interrupted while waiting for a scheduling job")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            release.countDown();
        }
    }

    @Test
    void aJobCalledInsideATransactionIsRefused() {
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().call(() -> Jobs.detached(() -> "never")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a scheduling job is not called inside a transaction");
    }
}

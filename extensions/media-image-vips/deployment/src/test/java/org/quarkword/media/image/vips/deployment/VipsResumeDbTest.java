package org.quarkword.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.interceptor.Interceptor;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.media.image.vips.runtime.VipsImageProcessor;
import org.quarkword.media.runtime.PendingProcessing;

/**
 * In the async mode, an upload an earlier run left pending is taken up again at start-up, after libvips's types and
 * the fallback are known: it ends with its sizes, never done without them.
 */
class VipsResumeDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.with(VipsResumeDbTest.class, Seeder.class)
            .overrideConfigKey("quarkus.libvips.required", "true")
            .overrideConfigKey("quarkword.media.processing", "async");

    /** Seeds the pending upload before any other start-up work: the database is migrated by then. */
    @Singleton
    public static class Seeder {

        volatile long post;

        void seed(
                @Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE - 100) StartupEvent event,
                @DataSource("quarkword") AgroalDataSource dataSource,
                TestStorage storage)
                throws IOException {
            post = Uploads.pendingJpeg(dataSource, storage, "left-pending.jpg", 1200, 800);
        }
    }

    @Inject
    Seeder seeder;

    @Inject
    BeanManager beans;

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Test
    void theProbeRunsBeforeTheResume() {
        Set<ObserverMethod<? super StartupEvent>> observers = beans.resolveObserverMethods(new StartupEvent());
        ObserverMethod<? super StartupEvent> probe = observers.stream()
                .filter(o -> o.getBeanClass().equals(VipsImageProcessor.class))
                .findFirst()
                .orElseThrow();
        ObserverMethod<? super StartupEvent> resume = observers.stream()
                .filter(o -> o.getBeanClass().equals(PendingProcessing.class))
                .findFirst()
                .orElseThrow();
        assertThat(probe.getPriority()).isEqualTo(Interceptor.Priority.PLATFORM_BEFORE);
        assertThat(probe.getPriority()).isLessThan(resume.getPriority());
    }

    @Test
    void aPendingUploadIsResumedWithItsSizes() throws Exception {
        assertThat(seeder.post).isPositive();
        long deadline = System.nanoTime() + 60_000_000_000L;
        String state;
        do {
            // pending until the run starts, partial while it makes the sizes
            state = Uploads.text(dataSource, "select processing from qw_media where post_id = ?", seeder.post);
            if (!"pending".equals(state) && !"partial".equals(state)) {
                break;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        assertThat(state).isEqualTo("done");
        assertThat(Uploads.text(dataSource, "select sizes from qw_media where post_id = ?", seeder.post))
                .contains("left-pending-300x200.jpg")
                .contains("medium_large");
    }
}

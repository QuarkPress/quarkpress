package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaChanges;
import org.quarkpress.media.MediaProcessed;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.media.Upload;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * The step after storing in the {@code async} processing mode: the background run is queued only once the step
 * returned, and never when it failed.
 */
class AfterStoreAsyncDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    AfterStoreAsyncDbTest.class, TestStorage.class, TestProcessor.class, Processed.class)
            .overrideConfigKey("quarkpress.media.processing", "async");

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    /** The attachments whose processing ended. */
    @Singleton
    public static class Processed {
        final Set<Long> posts = ConcurrentHashMap.newKeySet();

        void processed(@Observes MediaProcessed event) {
            posts.add(event.media().post().id().value());
        }
    }

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Media media;

    @Inject
    TestStorage storage;

    @Inject
    TestProcessor processor;

    @Inject
    TestClock clock;

    @Inject
    Processed processed;

    UserId carol;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        processed.posts.clear();
        TestData.siteOption(dataSource, TestData.MAIN, "timezone_string", "\"UTC\"");
        carol = TestData.memberOfMain(dataSource, "author");
    }

    /** Runs {@code work} with the site scope's transaction suspended; what it throws reaches the caller as itself. */
    private static MediaSnapshot outsideTransaction(Callable<MediaSnapshot> work) {
        try {
            return QuarkusTransaction.suspendingExisting().call(work);
        } catch (QuarkusTransactionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw wrapped;
        }
    }

    private MediaSnapshot upload(Media.AfterStore afterStore) throws Exception {
        return Identities.onSite(
                TestData.MAIN,
                Identities.user(carol),
                () -> outsideTransaction(() -> media.upload(jpeg(), afterStore)));
    }

    private static Upload jpeg() {
        try {
            Path dir = Files.createDirectories(Path.of("target", "after-store"));
            Path file = Files.write(dir.resolve(UUID.randomUUID().toString()), MediaFixtures.jpeg(400, 300));
            return Upload.builder("photo.jpg", file).declaredType("image/jpeg").build();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private MediaSnapshot read(PostId id) throws Exception {
        return Identities.onSite(TestData.MAIN, Identities.user(carol), () -> media.read(id))
                .orElseThrow();
    }

    /**
     * The attachment's processing state once its run has committed its end, within ten seconds: the end is announced
     * inside the run's last transaction, and the state is partial until the last size is saved.
     */
    private Processing awaitDone(PostId id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Processing state = read(id).processing();
        while (state != Processing.DONE && System.nanoTime() < deadline) {
            Thread.sleep(50);
            state = read(id).processing();
        }
        return state;
    }

    @Test
    void theBackgroundRunWaitsForTheStep() throws Exception {
        List<String> seen = new ArrayList<>();
        MediaSnapshot answer = upload(stored -> {
            try {
                // a run queued at the commit would have ended by now
                Thread.sleep(1000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            seen.add("processed " + processed.posts.contains(stored.post().id().value()));
            seen.add("encodes " + processor.encodes());
        });
        assertThat(seen).containsExactly("processed false", "encodes 0");
        assertThat(answer.processing()).isEqualTo(Processing.PENDING);

        assertThat(awaitDone(answer.post().id())).isEqualTo(Processing.DONE);
        assertThat(processed.posts).contains(answer.post().id().value());
    }

    @Test
    void anAfterStoreFailureLeavesThePendingAttachmentUnprocessed() throws Exception {
        IllegalStateException failure = new IllegalStateException("x");
        List<MediaSnapshot> stored = new ArrayList<>();
        assertThatThrownBy(() -> upload(attachment -> {
                    stored.add(attachment);
                    throw failure;
                }))
                .isSameAs(failure);
        PostId id = stored.get(0).post().id();
        Thread.sleep(2000);
        assertThat(processed.posts).doesNotContain(id.value());
        MediaSnapshot read = read(id);
        assertThat(read.processing()).isEqualTo(Processing.PENDING);
        assertThat(read.sizes()).isEmpty();
        assertThat(read.wordPressMetadata()).isEmpty();
        assertThat(processor.encodes()).isZero();
    }

    @Test
    void theAnswerIsReadAfterTheStep() throws Exception {
        MediaSnapshot answer = upload(stored -> media.update(
                stored.post().id(), MediaChanges.builder().title("Changed").build()));
        assertThat(answer.post().title()).isEqualTo("Changed");
        assertThat(answer.processing()).isEqualTo(Processing.PENDING);
        // its background run ends before the next test counts encodes
        assertThat(awaitDone(answer.post().id())).isEqualTo(Processing.DONE);
    }
}

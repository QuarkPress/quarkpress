package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaChanges;
import org.quarkpress.media.MediaProcessed;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.MediaStored;
import org.quarkpress.media.Upload;
import org.quarkpress.media.runtime.MediaRepository;
import org.quarkpress.posts.AttachmentAdded;
import org.quarkpress.posts.AttachmentEdited;
import org.quarkpress.posts.PostCreated;
import org.quarkpress.posts.PostSaved;
import org.quarkpress.posts.PostStatusChanged;
import org.quarkpress.posts.PostUpdated;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * The announcements of uploads and updates, as WordPress's hook log shows them: an upload adds the attachment
 * ({@code add_attachment}) before its media row exists, then announces the stored and the processed file; an update,
 * an alt-only one too, edits it ({@code edit_attachment}, {@code attachment_updated}); never a post save, update,
 * creation or status transition.
 */
class MediaEventsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            MediaApp.withoutStorage(MediaEventsDbTest.class, TestStorage.class, TestProcessor.class, Recorder.class);

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

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
    Recorder recorder;

    UserId bob;

    /** Records, in order, the media and post events fired in the transaction (no client proxy). */
    @Singleton
    public static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @Inject
        MediaRepository mediaRows;

        void added(@Observes AttachmentAdded event) {
            boolean row = mediaRows.findMedia(event.site(), event.post().id()).isPresent();
            seen.add("AttachmentAdded " + event.post().title() + (row ? " with" : " without") + " media row");
        }

        void edited(@Observes AttachmentEdited event) {
            seen.add("AttachmentEdited " + event.before().title() + ">"
                    + event.after().title() + " v" + event.before().version() + ">"
                    + event.after().version());
        }

        void stored(@Observes MediaStored event) {
            seen.add("MediaStored " + event.media().post().title());
        }

        void processed(@Observes MediaProcessed event) {
            seen.add("MediaProcessed " + event.media().post().title());
        }

        void saved(@Observes PostSaved event) {
            seen.add("PostSaved");
        }

        void updated(@Observes PostUpdated event) {
            seen.add("PostUpdated");
        }

        void created(@Observes PostCreated event) {
            seen.add("PostCreated");
        }

        void changed(@Observes PostStatusChanged event) {
            seen.add("PostStatusChanged");
        }
    }

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        recorder.seen.clear();
        TestData.siteOption(dataSource, TestData.MAIN, "timezone_string", "\"UTC\"");
        bob = TestData.memberOfMain(dataSource, "editor");
    }

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "media-events"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Runs {@code work} on the main site as bob, outside any transaction, as uploads must run. */
    private <T> T asBob(Callable<T> work) throws Exception {
        return Identities.onSite(TestData.MAIN, Identities.user(bob), () -> {
            try {
                return QuarkusTransaction.suspendingExisting().call(work);
            } catch (QuarkusTransactionException wrapped) {
                throw wrapped.getCause() instanceof Exception cause ? cause : wrapped;
            }
        });
    }

    private MediaSnapshot uploaded() throws Exception {
        Upload upload = Upload.builder("kite.jpg", file(MediaFixtures.jpeg(8, 8)))
                .declaredType("image/jpeg")
                .build();
        return asBob(() -> media.upload(upload));
    }

    @Test
    void anUploadAddsTheAttachmentThenStoresAndProcessesIt() throws Exception {
        uploaded();

        assertThat(recorder.seen)
                .containsExactly("AttachmentAdded kite without media row", "MediaStored kite", "MediaProcessed kite");
    }

    @Test
    void anUpdateEditsTheAttachment() throws Exception {
        MediaSnapshot upload = uploaded();
        int version = upload.post().version();
        recorder.seen.clear();

        asBob(() -> media.update(
                upload.post().id(), MediaChanges.builder().title("Kite").build()));

        assertThat(recorder.seen).containsExactly("AttachmentEdited kite>Kite v" + version + ">" + (version + 1));
    }

    @Test
    void anAltOnlyUpdateEditsTheAttachmentToo() throws Exception {
        MediaSnapshot upload = uploaded();
        int version = upload.post().version();
        recorder.seen.clear();
        clock.set(NOW.plusSeconds(2));

        asBob(() -> media.update(
                upload.post().id(), MediaChanges.builder().altText("A kite").build()));

        assertThat(recorder.seen).containsExactly("AttachmentEdited kite>kite v" + version + ">" + (version + 1));
    }
}

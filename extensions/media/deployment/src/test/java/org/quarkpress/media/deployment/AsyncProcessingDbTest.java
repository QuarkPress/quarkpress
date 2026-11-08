package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.MediaStored;
import org.quarkpress.media.Processing;
import org.quarkpress.media.Upload;
import org.quarkpress.media.WriteMode;
import org.quarkpress.media.runtime.PendingProcessing;
import org.quarkpress.media.runtime.SizeGenerator;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

/**
 * Processing after the upload has returned: the upload answers with the stored attachment, its sizes are made once the
 * storing transaction has committed, a failure is logged and leaves the attachment partial, and rows left pending by an
 * earlier run are processed again at start-up.
 */
class AsyncProcessingDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    AsyncProcessingDbTest.class, TestStorage.class, TestProcessor.class, Recorder.class)
            .overrideConfigKey("quarkpress.media.processing", "async")
            .setLogRecordPredicate(record -> record.getLoggerName().equals(SizeGenerator.class.getName()))
            .assertLogRecords(records -> assertThat(records)
                    .anySatisfy(record -> assertThat(record.getMessage()).contains("could not be decoded")));

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

    @Inject
    PendingProcessing pending;

    UserId carol;

    /** What an asynchronous observer of a stored upload finds in the database (no client proxy). */
    @Singleton
    public static class Recorder {
        final Map<Long, CompletableFuture<Long>> rowsSeenAfterCommit = new ConcurrentHashMap<>();

        @Inject
        @DataSource("quarkpress")
        AgroalDataSource dataSource;

        void storedAfterCommit(@ObservesAsync MediaStored event) {
            long id = event.media().post().id().value();
            long rows = Jdbc.count(dataSource, "select count(*) from qp_media where post_id = ?", id);
            rowsSeenAfterCommit
                    .computeIfAbsent(id, key -> new CompletableFuture<>())
                    .complete(rows);
        }
    }

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        TestData.siteOption(dataSource, TestData.MAIN, "timezone_string", "\"UTC\"");
        carol = TestData.memberOfMain(dataSource, "author");
    }

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "async-processing"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private MediaSnapshot upload(Upload.Builder upload) throws Exception {
        return Identities.onSite(
                TestData.MAIN,
                Identities.user(carol),
                () -> QuarkusTransaction.suspendingExisting().call(() -> media.upload(upload.build())));
    }

    private Upload.Builder jpeg(String name, int w, int h) {
        return Upload.builder(name, file(MediaFixtures.jpeg(w, h))).declaredType("image/jpeg");
    }

    private MediaSnapshot find(PostId id) throws Exception {
        return Identities.onSite(TestData.MAIN, Identities.user(carol), () -> media.find(id))
                .orElseThrow();
    }

    /** The attachment once {@code until} holds for it, within ten seconds. */
    private MediaSnapshot await(PostId id, Predicate<MediaSnapshot> until) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        MediaSnapshot current = find(id);
        while (!until.test(current) && System.nanoTime() < deadline) {
            Thread.sleep(50);
            current = find(id);
        }
        return current;
    }

    private String extraColumn(PostId id) {
        return Jdbc.strings(dataSource, "select extra from qp_media where post_id = ?", id.value())
                .get(0);
    }

    private String processing(PostId id) {
        return Jdbc.strings(dataSource, "select processing from qp_media where post_id = ?", id.value())
                .get(0);
    }

    @Test
    void theUploadAnswersPendingAndTheSizesFollow() throws Exception {
        MediaSnapshot m = upload(jpeg("x.jpg", 4000, 3000));
        assertThat(m.processing()).isEqualTo(Processing.PENDING);
        assertThat(m.storageKey()).isEqualTo("2026/11/x.jpg");
        assertThat(m.wordPressMetadata()).isEmpty();
        MediaSnapshot done = await(m.post().id(), s -> s.processing() == Processing.DONE);
        assertThat(done.processing()).isEqualTo(Processing.DONE);
        assertThat(done.sizes()).hasSize(6);
        assertThat(done.storageKey()).isEqualTo("2026/11/x-scaled.jpg");
        assertThat(done.originalImage()).contains("x.jpg");
        // the asynchronous observers run after the commit: they see the row
        assertThat(recorder.rowsSeenAfterCommit.get(m.post().id().value()).get(10, TimeUnit.SECONDS))
                .isEqualTo(1);
    }

    @Test
    void theUploadsOptionsHoldInTheBackgroundToo() throws Exception {
        MediaSnapshot m = upload(Upload.builder(
                        "o.jpg",
                        file(MediaFixtures.jpeg(4000, 3000, JpegMeta.none().withOrientation(6))))
                .declaredType("image/jpeg")
                .generateSubSizes(false));
        MediaSnapshot done = await(m.post().id(), s -> s.processing() != Processing.PENDING);
        assertThat(done.processing()).isEqualTo(Processing.DONE);
        assertThat(done.sizes()).isEmpty();
        assertThat(done.storageKey()).isEqualTo("2026/11/o.jpg");
        assertThat(done.width()).hasValue(4000);
        assertThat(done.extra().has("_quarkpress_upload")).isFalse();
        assertThat(processor.decodes()).isZero();
    }

    @Test
    void aFailureInTheBackgroundLeavesPartialAndIsLogged() throws Exception {
        processor.throwOnDecode(new IllegalStateException("the decoder crashed"));
        MediaSnapshot m = upload(jpeg("y.jpg", 1200, 800));
        MediaSnapshot after = await(m.post().id(), s -> s.processing() != Processing.PENDING);
        assertThat(after.processing()).isEqualTo(Processing.PARTIAL);
        assertThat(after.sizes()).isEmpty();
        assertThat(after.width()).hasValue(1200);
    }

    @Test
    void pendingRowsAreResumedAtStart() throws Exception {
        // only the rows of this test are pending
        Jdbc.update(dataSource, "update qp_media set processing = 'done' where processing = 'pending'");
        PostId old = pendingRow("old.jpg", 10, true, "{}");
        PostId lost = pendingRow("lost.jpg", 9, false, "{}");
        PostId older = pendingRow("older.jpg", 12, true, "{}");
        PostId young = pendingRow("young.jpg", 1, true, "{}");
        // the row whose file is gone ends failed and does not count; the others are processed past it
        assertThat(pending.resume(Instant.now())).isEqualTo(2);
        assertThat(processing(old)).isEqualTo("done");
        assertThat(processing(older)).isEqualTo("done");
        assertThat(processing(lost)).isEqualTo("failed");
        assertThat(processing(young)).isEqualTo("pending");
        MediaSnapshot resumed = find(old);
        assertThat(resumed.sizes().keySet()).containsExactly("medium", "large", "thumbnail", "medium_large");
        // nothing older is left: a second start finds nothing, not even the failed row
        assertThat(pending.resume(Instant.now())).isZero();
        assertThat(processing(lost)).isEqualTo("failed");
    }

    @Test
    void theUploadsOptionsAreKeptWithThePendingRow() throws Exception {
        Jdbc.update(dataSource, "update qp_media set processing = 'done' where processing = 'pending'");
        PostId kept =
                pendingRow("kept.jpg", 10, true, "{\"" + "_quarkpress_upload\": {\"generate_sub_sizes\": false}}");
        // kept in the row, never shown in the attachment
        assertThat(extraColumn(kept)).contains("_quarkpress_upload");
        assertThat(find(kept).extra().has("_quarkpress_upload")).isFalse();
        assertThat(pending.resume(Instant.now())).isEqualTo(1);
        assertThat(extraColumn(kept)).doesNotContain("_quarkpress_upload");
        MediaSnapshot m = find(kept);
        assertThat(m.processing()).isEqualTo(Processing.DONE);
        assertThat(m.sizes()).isEmpty();
        assertThat(m.storageKey()).isEqualTo("2026/11/kept.jpg");
        assertThat(m.extra().has("_quarkpress_upload")).isFalse();
        assertThat(m.wordPressMetadata().has("_quarkpress_upload")).isFalse();
        assertThat(processor.decodes()).isZero();
    }

    @Test
    void anErrorInTheBackgroundMarksTheAttachmentFailed() throws Exception {
        processor.errorOnNextDecode(new StackOverflowError("a decoder recursed too deep"));
        MediaSnapshot m = upload(jpeg("deep.jpg", 1200, 800));
        assertThat(m.processing()).isEqualTo(Processing.PENDING);
        MediaSnapshot after = await(m.post().id(), s -> s.processing() != Processing.PENDING);
        assertThat(after.processing()).isEqualTo(Processing.FAILED);
        assertThat(after.sizes()).isEmpty();
    }

    @Test
    void aBackgroundRunWhoseFirstReadFailsMarksTheAttachmentFailed() throws Exception {
        storage.failNextUrlAfterStore();
        MediaSnapshot m = upload(jpeg("unread.jpg", 1200, 800));
        // polled past the service, so that the background run's first read is the one that fails
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (processing(m.post().id()).equals("pending") && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(processing(m.post().id())).isEqualTo("failed");
    }

    @Test
    void aRowWhoseRunThrowsIsPassedForGood() throws Exception {
        Jdbc.update(dataSource, "update qp_media set processing = 'done' where processing = 'pending'");
        PostId poison = pendingRow("poison.jpg", 20, true, "{}");
        PostId next = pendingRow("next.jpg", 10, true, "{}");
        processor.errorOnNextDecode(new StackOverflowError("a decoder recursed too deep"));
        // the oldest row's run throws: logged, marked failed, and the newer row is still processed
        assertThat(pending.resume(Instant.now())).isEqualTo(1);
        assertThat(processing(poison)).isEqualTo("failed");
        assertThat(processing(next)).isEqualTo("done");
        // a later start no longer takes it: the rows after it are reached
        assertThat(pending.resume(Instant.now())).isZero();
        assertThat(processing(poison)).isEqualTo("failed");
    }

    @Test
    void atMostAHundredRowsAreTakenPerStart() throws Exception {
        Jdbc.update(dataSource, "update qp_media set processing = 'done' where processing = 'pending'");
        List<PostId> rows = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            // too small for any size: decoded, nothing cut
            rows.add(pendingRow("cap-" + i + ".jpg", 30, true, "{}", 100, 80));
        }
        assertThat(pending.resume(Instant.now())).isEqualTo(100);
        // the same age: the newest post is left for the next start
        for (PostId row : rows.subList(0, 100)) {
            assertThat(processing(row)).isEqualTo("done");
        }
        assertThat(processing(rows.get(100))).isEqualTo("pending");
        assertThat(pending.resume(Instant.now())).isEqualTo(1);
        assertThat(processing(rows.get(100))).isEqualTo("done");
    }

    /**
     * An attachment left pending, with a 1200x800 JPEG (stored only when {@code stored}), whose post was created
     * {@code minutes} minutes ago, with {@code extra} as its other metadata.
     */
    private PostId pendingRow(String name, int minutes, boolean stored, String extra) throws IOException {
        return pendingRow(name, minutes, stored, extra, 1200, 800);
    }

    /** As {@link #pendingRow(String, int, boolean, String)}, with a JPEG of {@code width}x{@code height}. */
    private PostId pendingRow(String name, int minutes, boolean stored, String extra, int width, int height)
            throws IOException {
        byte[] bytes = MediaFixtures.jpeg(width, height);
        String key = "2026/11/" + name;
        if (stored) {
            storage.put(key, file(bytes), "image/jpeg", WriteMode.CREATE_NEW);
        }
        PostId post = TestData.post(dataSource, TestData.MAIN, "attachment", "inherit", name, name);
        Jdbc.update(
                dataSource,
                "update qp_post set created_at = created_at - interval '" + minutes + "' minute where id = ?",
                post.value());
        Jdbc.update(
                dataSource,
                "insert into qp_media (post_id, site_id, storage_key, mime_type, file_size, width, height, sizes,"
                        + " image_meta, extra, alt_text, processing, stored_bytes, version) values (?, ?, ?,"
                        + " 'image/jpeg', ?, " + width + ", " + height + ", " + TestDb.json() + ", null, "
                        + TestDb.json()
                        + ", '', 'pending', ?, 0)",
                post.value(),
                TestData.MAIN.value(),
                key,
                (long) bytes.length,
                "{}",
                extra,
                (long) bytes.length);
        return post;
    }
}

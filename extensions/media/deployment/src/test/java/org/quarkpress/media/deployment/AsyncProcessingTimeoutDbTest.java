package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.media.WriteMode;
import org.quarkpress.media.runtime.AsyncProcessing;
import org.quarkpress.media.runtime.PendingProcessing;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * A background run that takes longer than the transaction timeout: no transaction stays open across it, so none times
 * out under it, the run is not logged as failed and a start-up resume counts it.
 */
class AsyncProcessingTimeoutDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    AsyncProcessingTimeoutDbTest.class, TestStorage.class, TestProcessor.class)
            .overrideConfigKey("quarkpress.media.processing", "async")
            .overrideConfigKey("quarkus.transaction-manager.default-transaction-timeout", "2s")
            .setLogRecordPredicate(record -> record.getLoggerName().equals(AsyncProcessing.class.getName()))
            .assertLogRecords(records -> assertThat(records)
                    .noneSatisfy(record -> assertThat(record.getMessage()).contains("failed")));

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    /** Longer than the transaction timeout, with room for the reaper to have acted. */
    static final long SLOW_DECODE_MILLIS = 5_000;

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
    PendingProcessing pending;

    UserId carol;

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
            Path dir = Files.createDirectories(Path.of("target", "async-timeout"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private String processing(PostId id) {
        return Jdbc.strings(dataSource, "select processing from qp_media where post_id = ?", id.value())
                .get(0);
    }

    @Test
    void aSlowResumeIsCounted() throws Exception {
        Jdbc.update(dataSource, "update qp_media set processing = 'done' where processing = 'pending'");
        byte[] bytes = MediaFixtures.jpeg(1200, 800);
        String key = "2026/11/slow.jpg";
        storage.put(key, file(bytes), "image/jpeg", WriteMode.CREATE_NEW);
        PostId post = TestData.post(dataSource, TestData.MAIN, "attachment", "inherit", "slow", "slow");
        Jdbc.update(
                dataSource,
                "update qp_post set created_at = created_at - interval '10' minute where id = ?",
                post.value());
        Jdbc.update(
                dataSource,
                "insert into qp_media (post_id, site_id, storage_key, mime_type, file_size, width, height, sizes,"
                        + " image_meta, extra, alt_text, processing, stored_bytes, version) values (?, ?, ?,"
                        + " 'image/jpeg', ?, 1200, 800, " + TestDb.json() + ", null, " + TestDb.json()
                        + ", '', 'pending', ?, 0)",
                post.value(),
                TestData.MAIN.value(),
                key,
                (long) bytes.length,
                "{}",
                "{}",
                (long) bytes.length);
        processor.slowDecodes(SLOW_DECODE_MILLIS);
        assertThat(pending.resume(Instant.now())).isEqualTo(1);
        assertThat(processing(post)).isEqualTo("done");
    }

    @Test
    void aSlowBackgroundRunIsNotLoggedAsFailed() throws Exception {
        processor.slowDecodes(SLOW_DECODE_MILLIS);
        MediaSnapshot m = Identities.onSite(
                TestData.MAIN,
                Identities.user(carol),
                () -> QuarkusTransaction.suspendingExisting()
                        .call(() -> media.upload(Upload.builder("slow-upload.jpg", file(MediaFixtures.jpeg(1200, 800)))
                                .declaredType("image/jpeg")
                                .build())));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        // the first save makes it partial; the last one done
        while (!processing(m.post().id()).equals("done") && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(processing(m.post().id())).isEqualTo("done");
        // the background run's scope has ended too: its failure would be logged after the last save
        Thread.sleep(1_000);
    }
}

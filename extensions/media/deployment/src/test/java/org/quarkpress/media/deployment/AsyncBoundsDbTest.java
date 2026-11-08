package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.media.Upload;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * Many uploads at once in the {@code async} mode: the background runs that hold a temporary copy of their file are
 * bounded by {@code async-runs}, whatever the number of uploads waiting, and every one of them still ends.
 */
class AsyncBoundsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    AsyncBoundsDbTest.class, TestStorage.class, TestProcessor.class)
            .overrideConfigKey("quarkpress.media.processing", "async")
            .overrideConfigKey("quarkpress.media.max-concurrent-decodes", "1")
            .overrideConfigKey("quarkpress.media.async-runs", "2");

    static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    static final int UPLOADS = 12;

    static final int ALLOWED_RUNS = 2;

    /** The one image every upload of the test sends. */
    static final byte[] IMAGE = MediaFixtures.jpeg(400, 300);

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
            Path dir = Files.createDirectories(Path.of("target", "async-bounds"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private MediaSnapshot upload(String name) throws Exception {
        Upload upload =
                Upload.builder(name, file(IMAGE)).declaredType("image/jpeg").build();
        return Identities.onSite(
                TestData.MAIN,
                Identities.user(carol),
                () -> QuarkusTransaction.suspendingExisting().call(() -> media.upload(upload)));
    }

    private MediaSnapshot find(PostId id) throws Exception {
        return Identities.onSite(TestData.MAIN, Identities.user(carol), () -> media.find(id))
                .orElseThrow();
    }

    /** The temporary files in the JVM's temporary directory named as the processing runs name their copies. */
    private static Set<Path> temporaryFiles() {
        Set<Path> files = new HashSet<>();
        Path dir = Path.of(System.getProperty("java.io.tmpdir"));
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "quarkpress-media-*.tmp")) {
            entries.forEach(files::add);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return files;
    }

    /**
     * How many of the files, other than those in {@code before}, are whole copies of {@link #IMAGE}: the copies of this
     * test's runs, whatever other processes write to the shared directory meanwhile.
     */
    private static int copiesOfTheUpload(Set<Path> before) {
        int copies = 0;
        for (Path file : temporaryFiles()) {
            if (before.contains(file)) {
                continue;
            }
            try {
                if (Arrays.equals(Files.readAllBytes(file), IMAGE)) {
                    copies++;
                }
            } catch (IOException gone) {
                // removed (or not readable) since the listing: not a copy held now
            }
        }
        return copies;
    }

    @Test
    void backgroundRunsNeverHoldMoreCopiesThanAllowed() throws Exception {
        Set<Path> before = temporaryFiles();
        CountDownLatch gate = new CountDownLatch(1);
        processor.holdDecodes(gate);
        List<PostId> ids = new ArrayList<>();
        int most = 0;
        try {
            for (int i = 0; i < UPLOADS; i++) {
                MediaSnapshot stored = upload("many-" + i + ".jpg");
                assertThat(stored.processing()).isEqualTo(Processing.PENDING);
                ids.add(stored.post().id());
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < end) {
                most = Math.max(most, copiesOfTheUpload(before));
                Thread.sleep(20);
            }
        } finally {
            gate.countDown();
        }
        // one run decodes behind the gate while the next holds its copy, waiting for the decode permit: exactly the
        // configured number, never the uploads waiting behind them
        assertThat(most).as("temporary copies held at once").isEqualTo(ALLOWED_RUNS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        for (PostId id : ids) {
            MediaSnapshot current = find(id);
            while (current.processing() != Processing.DONE && System.nanoTime() < deadline) {
                Thread.sleep(50);
                current = find(id);
            }
            assertThat(current.processing()).as("attachment %s", id.value()).isEqualTo(Processing.DONE);
        }
    }
}

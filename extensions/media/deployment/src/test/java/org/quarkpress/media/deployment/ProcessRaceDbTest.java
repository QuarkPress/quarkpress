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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 * Processing runs of one attachment at the same time, with decodes allowed to overlap: the row lock and the checks
 * for sizes another run saved keep every size made and stored once, and a run that fails never overwrites the run
 * that finished first.
 */
class ProcessRaceDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    ProcessRaceDbTest.class, TestStorage.class, TestProcessor.class)
            .overrideConfigKey("quarkpress.media.max-concurrent-decodes", "2")
            .overrideConfigKey("quarkpress.media.output-formats.\"image/gif\"", "image/png");

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
        clock.set(Instant.parse("2026-11-05T10:00:00Z"));
        storage.clear();
        processor.reset();
        TestData.siteOption(dataSource, TestData.MAIN, "timezone_string", "\"UTC\"");
        carol = TestData.memberOfMain(dataSource, "author");
    }

    private static Upload upload(String name, byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "process-race"));
            Path file = Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
            return Upload.builder(name, file).declaredType("image/jpeg").build();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private MediaSnapshot outside(java.util.concurrent.Callable<MediaSnapshot> work) throws Exception {
        return Identities.onSite(
                TestData.MAIN,
                Identities.user(carol),
                () -> QuarkusTransaction.suspendingExisting().call(work));
    }

    private MediaSnapshot process(PostId id) throws Exception {
        return outside(() -> media.process(id));
    }

    /** A 1200x800 attachment with its base metadata only: its decode failed at upload. */
    private MediaSnapshot baseOnly(String name) throws Exception {
        processor.throwOnDecode(new IllegalStateException("not now"));
        MediaSnapshot base = outside(() -> media.upload(upload(name, MediaFixtures.jpeg(1200, 800))));
        assertThat(base.sizes()).isEmpty();
        processor.reset();
        return base;
    }

    private MediaSnapshot find(PostId id) throws Exception {
        return Identities.onSite(TestData.MAIN, Identities.user(carol), () -> media.find(id))
                .orElseThrow();
    }

    @Test
    void twoRunsSavingTheSameSizeAtOnceStoreItOnce() throws Exception {
        PostId id = baseOnly("x.jpg").post().id();
        int puts = storage.puts();
        // both runs encode their first size, then reach its save together
        processor.encodeBarrier(2);
        List<Object> results = Races.run(List.of(() -> process(id), () -> process(id)));
        assertThat(results).allMatch(result -> result instanceof MediaSnapshot);
        MediaSnapshot after = find(id);
        assertThat(after.sizes().keySet()).containsExactly("medium", "large", "thumbnail", "medium_large");
        assertThat(after.processing()).isEqualTo(Processing.DONE);
        assertThat(storage.puts() - puts).as("files stored").isEqualTo(4);
        assertThat(storage.keys()).noneMatch(key -> key.contains("-1."));
    }

    @Test
    void aRunThatFindsSizesMadeMeanwhileSkipsThem() throws Exception {
        PostId id = baseOnly("y.jpg").post().id();
        // while the first run decodes, a second run makes every size
        AtomicReference<Object> second = new AtomicReference<>();
        processor.duringNextDecode(() -> {
            try {
                second.set(process(id));
            } catch (Exception failure) {
                second.set(failure);
            }
        });
        MediaSnapshot first = process(id);
        assertThat(second.get()).isInstanceOf(MediaSnapshot.class);
        assertThat(first.sizes()).hasSize(4);
        assertThat(first.processing()).isEqualTo(Processing.DONE);
        // the first run planned all four but encoded none: they were there before it cut them
        assertThat(processor.encodes()).isEqualTo(4);
        assertThat(processor.decodes()).isEqualTo(2);
    }

    @Test
    void aFailedRunNeverOverwritesTheRunThatFinishedFirst() throws Exception {
        MediaSnapshot stored = Identities.onSite(
                TestData.MAIN,
                Identities.user(carol),
                () -> media.store(upload("z.jpg", MediaFixtures.jpeg(4000, 3000))));
        PostId id = stored.post().id();
        // while the first run decodes, a second run processes the upload completely; then the first run's decode fails
        AtomicReference<Object> second = new AtomicReference<>();
        processor.duringNextDecode(() -> {
            try {
                second.set(process(id));
            } catch (Exception failure) {
                second.set(failure);
            }
            processor.throwOnDecode(new IllegalStateException("too late"));
        });
        process(id);
        assertThat(second.get()).isInstanceOf(MediaSnapshot.class);
        MediaSnapshot after = find(id);
        assertThat(after.storageKey()).isEqualTo("2026/11/z-scaled.jpg");
        assertThat(after.width()).hasValue(2560);
        assertThat(after.height()).hasValue(1920);
        assertThat(after.sizes()).hasSize(6);
        assertThat(after.processing()).isEqualTo(Processing.DONE);
    }

    @Test
    void aPostProcessHonoursTheUploadsFormatChoice() throws Exception {
        // GIFs are mapped to PNG here; this upload asked for no conversion, and one of its sizes failed
        processor.failSize(150, 150);
        Path dir = Files.createDirectories(Path.of("target", "process-race"));
        Path file = Files.write(dir.resolve(UUID.randomUUID().toString()), MediaFixtures.gif(1200, 800, 1));
        Upload gif = Upload.builder("g.gif", file)
                .declaredType("image/gif")
                .convertFormat(false)
                .build();
        MediaSnapshot m = outside(() -> media.upload(gif));
        assertThat(m.storageKey()).isEqualTo("2026/11/g.gif");
        assertThat(m.processing()).isEqualTo(Processing.PARTIAL);
        assertThat(m.sizes().get("medium").file()).isEqualTo("g-300x200.gif");
        processor.reset();
        MediaSnapshot resumed = process(m.post().id());
        assertThat(resumed.storageKey()).isEqualTo("2026/11/g.gif");
        assertThat(resumed.originalImage()).isEmpty();
        assertThat(resumed.sizes().get("thumbnail").file()).isEqualTo("g-150x150.gif");
        assertThat(resumed.processing()).isEqualTo(Processing.DONE);
        assertThat(storage.keys()).noneMatch(key -> key.endsWith(".png"));
    }
}

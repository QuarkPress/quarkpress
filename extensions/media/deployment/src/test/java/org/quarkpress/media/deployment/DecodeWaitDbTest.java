package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.configuration.DurationConverter;
import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.runtime.configuration.MemorySizeConverter;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.media.Upload;
import org.quarkpress.media.runtime.DecodeLimiter;
import org.quarkpress.media.runtime.MediaConfig;
import org.quarkpress.media.runtime.SizeGenerator;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * A run that waits longer than {@code decode-wait} for a decode permit fails as {@code failed}, logged, instead of
 * waiting forever behind a permit that is never released.
 */
class DecodeWaitDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    DecodeWaitDbTest.class, TestStorage.class, TestProcessor.class)
            .overrideConfigKey("quarkpress.media.max-concurrent-decodes", "1")
            .overrideConfigKey("quarkpress.media.decode-wait", "500ms");

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
    DecodeLimiter limiter;

    UserId carol;

    final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    final Handler recorder = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        storage.clear();
        processor.reset();
        TestData.siteOption(dataSource, TestData.MAIN, "timezone_string", "\"UTC\"");
        carol = TestData.memberOfMain(dataSource, "author");
        warnings.clear();
        Logger.getLogger(SizeGenerator.class.getName()).addHandler(recorder);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(SizeGenerator.class.getName()).removeHandler(recorder);
    }

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "decode-wait"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private MediaSnapshot upload(String name) throws Exception {
        Upload upload = Upload.builder(name, file(MediaFixtures.jpeg(400, 300)))
                .declaredType("image/jpeg")
                .build();
        return Identities.onSite(
                TestData.MAIN,
                Identities.user(carol),
                () -> QuarkusTransaction.suspendingExisting().call(() -> media.upload(upload)));
    }

    private List<String> warningTexts() {
        List<String> texts = new ArrayList<>();
        for (LogRecord record : warnings) {
            Object[] parameters = record.getParameters();
            texts.add(parameters == null ? record.getMessage() : String.format(record.getMessage(), parameters));
        }
        return texts;
    }

    @Test
    void aStuckDecodePermitFailsTheRunAfterTheWait() throws Exception {
        MediaSnapshot stored;
        long elapsed;
        try (DecodeLimiter.Permit held = limiter.acquire(Duration.ofSeconds(5))) {
            long start = System.nanoTime();
            stored = upload("stuck.jpg");
            elapsed = System.nanoTime() - start;
        }
        assertThat(Duration.ofNanos(elapsed)).isLessThan(Duration.ofSeconds(5));
        assertThat(stored.processing()).isEqualTo(Processing.FAILED);
        MediaSnapshot found = Identities.onSite(
                        TestData.MAIN,
                        Identities.user(carol),
                        () -> media.find(stored.post().id()))
                .orElseThrow();
        assertThat(found.processing()).isEqualTo(Processing.FAILED);
        assertThat(processor.decodes()).isZero();
        assertThat(warningTexts())
                .anySatisfy(text -> assertThat(text)
                        .contains("waited 500 ms for a decode permit")
                        .contains("attachment " + stored.post().id().value() + " "));
    }

    @Test
    void theWaitIsDeclaredWithItsDefault() {
        SmallRyeConfig defaults = new SmallRyeConfigBuilder()
                .withConverter(MemorySize.class, 100, new MemorySizeConverter())
                .withConverter(Duration.class, 100, new DurationConverter())
                .withMapping(MediaConfig.class)
                .build();
        MediaConfig config = defaults.getConfigMapping(MediaConfig.class);
        assertThat(config.decodeWait()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.asyncRuns()).isEmpty();
    }
}

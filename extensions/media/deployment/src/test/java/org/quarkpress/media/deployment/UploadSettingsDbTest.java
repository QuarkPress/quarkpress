package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaException;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/** Uploads on a multisite installation that accepts images its processor cannot make sizes for. */
class UploadSettingsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.withoutStorage(
                    UploadSettingsDbTest.class, TestStorage.class, TestProcessor.class, TestMultisiteMode.class)
            .overrideConfigKey("quarkpress.media.accept-unprocessable-images", "true");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Media media;

    @Inject
    TestStorage storage;

    @Inject
    TestClock clock;

    UserId carol;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse("2026-11-05T10:00:00Z"));
        storage.clear();
        TestData.siteOption(dataSource, TestData.MAIN, "timezone_string", "\"UTC\"");
        carol = TestData.memberOfMain(dataSource, "author");
    }

    private MediaSnapshot store(String name, byte[] bytes, String declaredType) throws Exception {
        Path dir;
        try {
            dir = Files.createDirectories(Path.of("target", "upload-settings"));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        Path file = Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        return Identities.onSite(
                TestData.MAIN,
                Identities.user(carol),
                () -> media.store(
                        Upload.builder(name, file).declaredType(declaredType).build()));
    }

    @Test
    void imagesTheProcessorCannotHandleAreAcceptedWhenConfigured() throws Exception {
        assertThat(store("x.webp", MediaFixtures.webp(), "image/webp"))
                .extracting(MediaSnapshot::storageKey, MediaSnapshot::mimeType)
                .containsExactly("2026/11/x.webp", "image/webp");
    }

    @Test
    void anEmptyFileOnMultisiteGetsTheShortMessage() {
        assertThatThrownBy(() -> store("x.txt", new byte[0], "text/plain"))
                .isInstanceOfSatisfying(
                        MediaException.class, e -> assertThat(e.code()).isEqualTo("upload_error"))
                .hasMessage("File is empty. Please upload something more substantial.");
        assertThat(storage.puts()).isZero();
    }
}

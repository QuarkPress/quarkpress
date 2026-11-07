package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.media.Upload;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

/** Without an image processor module, images keep the metadata WordPress stores without an image editor. */
class MediaProcessWithoutProcessorDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            MediaApp.withoutStorage(MediaProcessWithoutProcessorDbTest.class, TestStorage.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Media media;

    @Inject
    TestStorage storage;

    @Inject
    TestClock clock;

    @Test
    void withoutAProcessorImagesKeepTheirBaseMetadata() throws Exception {
        clock.set(Instant.parse("2026-11-05T10:00:00Z"));
        storage.clear();
        TestData.siteOption(dataSource, TestData.MAIN, "timezone_string", "\"UTC\"");
        UserId carol = TestData.memberOfMain(dataSource, "author");
        Path dir = Files.createDirectories(Path.of("target", "media-process-without-processor"));
        Path file = Files.write(
                dir.resolve(UUID.randomUUID().toString()),
                MediaFixtures.jpeg(4000, 3000, JpegMeta.none().withIptc(5, "Harbour at dawn")));
        Upload upload = Upload.builder("x.jpg", file)
                .declaredType("image/jpeg")
                .generateSubSizes(false)
                .build();
        MediaSnapshot m = Identities.onSite(
                TestData.MAIN,
                Identities.user(carol),
                () -> QuarkusTransaction.suspendingExisting().call(() -> media.upload(upload)));
        ObjectNode metadata = m.wordPressMetadata();
        List<String> keys = new ArrayList<>();
        metadata.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("width", "height", "file", "filesize", "sizes", "image_meta");
        assertThat(metadata.get("width").intValue()).isEqualTo(4000);
        assertThat(metadata.get("height").intValue()).isEqualTo(3000);
        assertThat(metadata.get("file").textValue()).isEqualTo("2026/11/x.jpg");
        assertThat(metadata.get("sizes").isEmpty()).isTrue();
        assertThat(metadata.get("image_meta").get("title").textValue()).isEqualTo("Harbour at dawn");
        assertThat(m.post().title()).isEqualTo("Harbour at dawn");
        assertThat(m.processing()).isEqualTo(Processing.DONE);
        assertThat(storage.keys()).containsExactly("2026/11/x.jpg");
    }
}

package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSize;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.media.image.imageio.runtime.ImageIoProcessor;
import org.quarkpress.media.image.vips.runtime.VipsFormats;
import org.quarkpress.media.image.vips.runtime.VipsImageProcessor;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * libvips cannot be loaded and is not required: ImageIO, the fallback, processes every image, and the start says so
 * once, without the library's path (which quarkus-libvips's own warning carries).
 */
class LibvipsMissingFallbackDbTest {

    static final String MISSING = "/nonexistent/libvips.so.42";

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.with(LibvipsMissingFallbackDbTest.class)
            .overrideConfigKey("quarkus.libvips.library", MISSING)
            .setLogRecordPredicate(record -> record.getLoggerName().startsWith("org.quarkpress.media.image.vips"))
            .assertLogRecords(LibvipsMissingFallbackDbTest::oneWarning);

    @Inject
    ImageProcessor processor;

    @Inject
    VipsImageProcessor vips;

    @Inject
    Media media;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void imageIoTakesOverAndTheStartWarns() throws Exception {
        assertThat(ClientProxy.unwrap(processor)).isInstanceOf(VipsImageProcessor.class);
        assertThat(vips.formats()).isEqualTo(VipsFormats.NONE);
        assertThat(ClientProxy.unwrap(vips.fallback().orElseThrow())).isInstanceOf(ImageIoProcessor.class);
        assertThat(processor.supports("image/jpeg")).isTrue();
        assertThat(processor.supports("image/webp")).isFalse();
        // ImageIO writes no BMP copies either
        assertThat(processor.writes("image/bmp")).isFalse();
        MediaSnapshot m = Uploads.upload(
                media,
                Uploads.author(dataSource),
                Uploads.of("photo.jpg", MediaFixtures.jpeg(1200, 800), "image/jpeg"));
        assertThat(m.processing()).isEqualTo(Processing.DONE);
        assertThat(m.sizes().keySet()).containsExactlyInAnyOrder("medium", "large", "thumbnail", "medium_large");
        assertThat(m.sizes().get("medium").file()).isEqualTo("photo-300x200.jpg");
        assertThat(m.sizes().values()).extracting(MediaSize::mimeType).containsOnly("image/jpeg");
    }

    /** BMP sizes are JPEG with ImageIO too, as WordPress with Imagick writes them; the main file is kept. */
    @Test
    void bmpSizesAreJpeg() throws Exception {
        MediaSnapshot m = Uploads.upload(
                media,
                Uploads.author(dataSource),
                Uploads.of("picture.bmp", MediaFixtures.bmp(1200, 800), "image/bmp"));
        assertThat(m.processing()).isEqualTo(Processing.DONE);
        assertThat(m.attachedFile()).endsWith("/picture.bmp");
        assertThat(m.originalImage()).isEmpty();
        assertThat(m.sizes().get("medium").file()).isEqualTo("picture-300x200.jpg");
        assertThat(m.sizes().values()).extracting(MediaSize::mimeType).containsOnly("image/jpeg");
    }

    static void oneWarning(List<LogRecord> records) {
        List<String> warnings = records.stream()
                .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .map(LogRecord::getMessage)
                .toList();
        assertThat(warnings)
                .containsExactly("libvips is not available; images are processed by ImageIoProcessor instead");
        assertThat(records)
                .noneMatch(r -> r.getLevel().intValue() >= Level.INFO.intValue()
                        && String.valueOf(r.getMessage()).contains("/nonexistent"));
    }
}

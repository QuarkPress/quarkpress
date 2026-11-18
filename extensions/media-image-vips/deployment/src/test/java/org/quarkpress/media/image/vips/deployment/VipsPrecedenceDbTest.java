package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSize;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.media.image.imageio.runtime.ImageIoProcessor;
import org.quarkpress.media.image.vips.runtime.VipsImageProcessor;
import org.quarkpress.media.runtime.MediaConfig;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.sitenetsoft.quarkus.libvips.LibvipsConfig;

/**
 * With the vips and ImageIO modules and no choice made, libvips is the image processor; ImageIO, found as the
 * fallback, decodes the types libvips does not (BMP, whose sizes libvips cannot write and so are JPEG, as WordPress
 * with Imagick writes them, the main file kept), and a type libvips decodes is never handed to ImageIO, even when
 * libvips fails on it.
 */
class VipsPrecedenceDbTest {

    static final List<String> FOUR = List.of("medium", "large", "thumbnail", "medium_large");

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.with(VipsPrecedenceDbTest.class)
            .overrideConfigKey("quarkus.libvips.required", "true")
            .setLogRecordPredicate(record -> record.getLoggerName().startsWith("org.quarkpress.media.image.vips"))
            .assertLogRecords(VipsPrecedenceDbTest::theStartIsLoggedOnce);

    @Inject
    ImageProcessor processor;

    @Inject
    VipsImageProcessor vips;

    @Inject
    Media media;

    @Inject
    MediaConfig config;

    @Inject
    TestStorage storage;

    @Inject
    LibvipsConfig libvipsConfig;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void vipsIsTheImageProcessor() {
        assertThat(ClientProxy.unwrap(processor)).isInstanceOf(VipsImageProcessor.class);
        // the module's own start-up probe ran
        assertThat(vips.formats().writes("image/jpeg")).isTrue();
    }

    @Test
    void theFallbackIsImageIo() {
        assertThat(vips.fallback()).isPresent();
        assertThat(ClientProxy.unwrap(vips.fallback().get())).isInstanceOf(ImageIoProcessor.class);
    }

    @Test
    void theFallbackAnswersForTheTypesLibvipsLacks() throws Exception {
        assertThat(vips.formats().supports("image/bmp")).isFalse();
        assertThat(processor.supports("image/bmp")).isTrue();
        // copies are written by libvips alone while it is available
        assertThat(processor.writes("image/bmp")).isFalse();
        Path bmp = Files.write(Files.createTempFile(Path.of("target"), "fallback-", ".tmp"), MediaFixtures.bmp(40, 30));
        assertThat(processor.read(bmp)).contains(new ImageInfo("image/bmp", 40, 30));
        // a type neither processor handles
        assertThat(processor.supports("image/x-unknown")).isFalse();
        assertThat(processor.writes("image/x-unknown")).isFalse();
    }

    @Test
    void aTypeLibvipsDecodesIsNeverRetriedWithImageIo() throws Exception {
        byte[] png = MediaFixtures.png(400, 300);
        Path half = Files.write(
                Files.createTempFile(Path.of("target"), "half-", ".tmp"), Arrays.copyOf(png, png.length / 2));
        assertThatThrownBy(() -> processor.decode(half, "image/png").close())
                .isInstanceOf(ImageProcessingException.class)
                .hasMessage("libvips could not decode the image.");
        // a type neither decodes
        Path tiff = Files.write(Files.createTempFile(Path.of("target"), "tiff-", ".tmp"), MediaFixtures.tiff(30, 20));
        assertThatThrownBy(() -> processor.decode(tiff, "image/tiff").close())
                .isInstanceOf(ImageProcessingException.class)
                .hasMessage("No image processor reads image/tiff.");
    }

    /** QuarkPress's own limits (the HTTP body limit and the pixel limit by default) are below libvips's defaults. */
    @Test
    void libvipsKeepsItsOwnLimitsWhenHigher() {
        assertThat(libvipsConfig.limits().maxInputSize().asLongValue()).isEqualTo(128L << 20);
        assertThat(libvipsConfig.limits().maxInputPixels()).isEqualTo(268_402_689L);
    }

    @Test
    void bmpIsNotMapped() {
        assertThat(config.effectiveOutputFormats()).doesNotContainKey("image/bmp");
    }

    @Test
    void aBmpDecodedByImageIoGetsJpegSizes() throws Exception {
        UserId author = Uploads.author(dataSource);
        MediaSnapshot m =
                Uploads.upload(media, author, Uploads.of("photo.bmp", MediaFixtures.bmp(1200, 800), "image/bmp"));
        assertThat(m.mimeType()).isEqualTo("image/bmp");
        // the main file is kept, as WordPress with Imagick keeps it (no output mapping is involved)
        assertThat(m.attachedFile()).endsWith("/photo.bmp");
        assertThat(m.originalImage()).isEmpty();
        assertThat(m.processing()).isEqualTo(Processing.DONE);
        assertThat(m.sizes().keySet()).containsExactlyInAnyOrderElementsOf(FOUR);
        assertThat(m.sizes().get("medium").file()).isEqualTo("photo-300x200.jpg");
        assertThat(m.sizes().get("large").file()).isEqualTo("photo-1024x683.jpg");
        String folder = m.storageKey().substring(0, m.storageKey().lastIndexOf('/') + 1);
        for (Map.Entry<String, MediaSize> size : m.sizes().entrySet()) {
            assertThat(size.getValue().mimeType()).as(size.getKey()).isEqualTo("image/jpeg");
            byte[] bytes = storage.bytes(folder + size.getValue().file());
            assertThat(bytes[0] & 0xff).as(size.getKey()).isEqualTo(0xff);
            assertThat(bytes[1] & 0xff).as(size.getKey()).isEqualTo(0xd8);
        }
    }

    @Test
    void aWebpUploadIsAcceptedAndGetsWebpSizes() throws Exception {
        assumeTrue(
                vips.formats().writes("image/webp") || Boolean.getBoolean("vips.require-all-formats"),
                "SKIPPED (libvips lacks webpsave): install libvips with libwebp");
        UserId author = Uploads.author(dataSource);
        MediaSnapshot m = Uploads.upload(
                media, author, Uploads.of("photo.webp", MediaFixtures.webpLossy(3000, 2000), "image/webp"));
        assertThat(m.processing()).isEqualTo(Processing.DONE);
        assertThat(m.attachedFile()).endsWith("/photo-scaled.webp");
        assertThat(m.sizes().get("medium").file()).isEqualTo("photo-300x200.webp");
        assertThat(m.sizes()).hasSize(6);
        String folder = m.storageKey().substring(0, m.storageKey().lastIndexOf('/') + 1);
        for (Map.Entry<String, MediaSize> size : m.sizes().entrySet()) {
            assertThat(size.getValue().mimeType()).as(size.getKey()).isEqualTo("image/webp");
            assertThat(size.getValue().file()).as(size.getKey()).endsWith(".webp");
            byte[] bytes = storage.bytes(folder + size.getValue().file());
            assertThat(new String(bytes, 8, 4, StandardCharsets.ISO_8859_1))
                    .as(size.getKey())
                    .isEqualTo("WEBP");
        }
    }

    /** One INFO line names what libvips does and the processor of the other types; nothing is warned. */
    static void theStartIsLoggedOnce(List<LogRecord> records) {
        List<String> infos = records.stream()
                .filter(r -> r.getLevel().intValue() == Level.INFO.intValue())
                .map(LogRecord::getMessage)
                .filter(m -> m.startsWith("libvips "))
                .toList();
        assertThat(infos).hasSize(1);
        assertThat(infos.getFirst())
                .contains(": sub-sizes in image/jpeg")
                .endsWith("; other types by ImageIoProcessor");
        assertThat(records).noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue());
    }
}

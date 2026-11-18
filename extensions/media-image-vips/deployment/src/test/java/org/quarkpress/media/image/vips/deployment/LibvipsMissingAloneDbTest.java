package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaException;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.media.image.vips.runtime.VipsImageProcessor;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * libvips cannot be loaded, is not required, and ImageIO is not installed: images are refused as WordPress refuses
 * them without an image editor, unless they ask for no sizes.
 */
class LibvipsMissingAloneDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.withoutImageIo(LibvipsMissingAloneDbTest.class)
            .overrideConfigKey("quarkus.libvips.library", LibvipsMissingFallbackDbTest.MISSING)
            .setLogRecordPredicate(record -> record.getLoggerName().startsWith("org.quarkpress.media.image.vips"))
            .assertLogRecords(LibvipsMissingAloneDbTest::oneWarning);

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
    void imagesAreRefusedAsWithoutAnEditor() throws Exception {
        assertThat(vips.fallback()).isEmpty();
        assertThat(processor.supports("image/jpeg")).isFalse();
        UserId author = Uploads.author(dataSource);
        MediaException refused = catchThrowableOfType(
                MediaException.class,
                () -> Uploads.upload(
                        media, author, Uploads.of("photo.jpg", MediaFixtures.jpeg(1200, 800), "image/jpeg")));
        assertThat(refused).isNotNull();
        assertThat(refused.code()).isEqualTo("rest_upload_image_type_not_supported");

        MediaSnapshot stored = Uploads.upload(
                media,
                author,
                Uploads.of("kept.jpg", MediaFixtures.jpeg(1200, 800), "image/jpeg")
                        .generateSubSizes(false));
        assertThat(stored.processing()).isEqualTo(Processing.DONE);
        assertThat(stored.sizes()).isEmpty();
        assertThat(stored.width()).hasValue(1200);
    }

    static void oneWarning(List<LogRecord> records) {
        List<String> warnings = records.stream()
                .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                .map(LogRecord::getMessage)
                .toList();
        assertThat(warnings).containsExactly("libvips is not available; images get no generated sizes");
    }
}

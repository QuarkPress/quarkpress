package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaException;
import org.quarkpress.media.image.imageio.runtime.ImageIoProcessor;
import org.quarkpress.media.image.vips.runtime.VipsImageProcessor;
import org.quarkpress.media.runtime.MediaConfig;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * Choosing ImageIO by name with the vips module installed: ImageIO is the image processor, the libvips processor is
 * not in the application, and a WebP upload is refused as a type no editor makes sizes for.
 */
class ImageIoChosenDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.with(ImageIoChosenDbTest.class)
            .overrideConfigKey("quarkus.libvips.required", "true")
            .overrideConfigKey("quarkpress.media.image-processor", "imageio");

    @Inject
    ImageProcessor processor;

    @Inject
    Media media;

    @Inject
    MediaConfig config;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void imageIoIsTheImageProcessorAndWebpIsRefused() {
        assertThat(ClientProxy.unwrap(processor)).isInstanceOf(ImageIoProcessor.class);
        assertThat(Arc.container().instance(VipsImageProcessor.class).isAvailable())
                .isFalse();
        MediaException refused = catchThrowableOfType(
                MediaException.class,
                () -> Uploads.upload(
                        media,
                        Uploads.author(dataSource),
                        Uploads.of("photo.webp", MediaFixtures.webpLossy(3000, 2000), "image/webp")));
        assertThat(refused).isNotNull();
        assertThat(refused.code()).isEqualTo("rest_upload_image_type_not_supported");
    }

    @Test
    void bmpSizesKeepTheirType() {
        assertThat(config.effectiveOutputFormats()).doesNotContainKey("image/bmp");
    }
}

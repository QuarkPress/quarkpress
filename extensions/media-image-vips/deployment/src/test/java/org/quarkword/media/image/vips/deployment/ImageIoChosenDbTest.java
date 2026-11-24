package org.quarkword.media.image.vips.deployment;

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
import org.quarkword.media.ImageProcessor;
import org.quarkword.media.Media;
import org.quarkword.media.MediaException;
import org.quarkword.media.image.imageio.runtime.ImageIoProcessor;
import org.quarkword.media.image.vips.runtime.VipsImageProcessor;
import org.quarkword.media.runtime.MediaConfig;
import org.quarkword.testing.wprestparity.MediaFixtures;

/**
 * Choosing ImageIO by name with the vips module installed: ImageIO is the image processor, the libvips processor is
 * not in the application, and a WebP upload is refused as a type no editor makes sizes for.
 */
class ImageIoChosenDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.with(ImageIoChosenDbTest.class)
            .overrideConfigKey("quarkus.libvips.required", "true")
            .overrideConfigKey("quarkword.media.image-processor", "imageio");

    @Inject
    ImageProcessor processor;

    @Inject
    Media media;

    @Inject
    MediaConfig config;

    @Inject
    @DataSource("quarkword")
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

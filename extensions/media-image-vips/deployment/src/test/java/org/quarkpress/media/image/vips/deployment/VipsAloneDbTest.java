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
import org.quarkpress.testing.wprestparity.MediaFixtures;

/** The vips module without ImageIO: libvips is the image processor, with no fallback, and a BMP upload is refused. */
class VipsAloneDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            VipsApp.withoutImageIo(VipsAloneDbTest.class).overrideConfigKey("quarkus.libvips.required", "true");

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
    void bmpIsRefusedWithoutImageIo() {
        assertThat(Arc.container().instance(ImageIoProcessor.class).isAvailable())
                .isFalse();
        assertThat(ClientProxy.unwrap(processor)).isInstanceOf(VipsImageProcessor.class);
        assertThat(vips.fallback()).isEmpty();
        assertThat(processor.supports("image/bmp")).isFalse();
        assertThat(processor.writes("image/bmp")).isFalse();
        MediaException refused = catchThrowableOfType(
                MediaException.class,
                () -> Uploads.upload(
                        media,
                        Uploads.author(dataSource),
                        Uploads.of("photo.bmp", MediaFixtures.bmp(1200, 800), "image/bmp")));
        assertThat(refused).isNotNull();
        assertThat(refused.code()).isEqualTo("rest_upload_image_type_not_supported");
    }
}

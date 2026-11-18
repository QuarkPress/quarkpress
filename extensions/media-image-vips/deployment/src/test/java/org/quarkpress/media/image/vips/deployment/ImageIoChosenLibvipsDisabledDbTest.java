package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.quarkpress.media.MediaSize;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Processing;
import org.quarkpress.media.image.imageio.runtime.ImageIoProcessor;
import org.quarkpress.media.image.vips.runtime.VipsImageProcessor;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.sitenetsoft.quarkus.libvips.Libvips;

/**
 * Choosing ImageIO by name and switching quarkus-libvips off, as the module's README recommends: the application builds
 * and starts without loading libvips (it is required and its library path names no file, so a load would fail the
 * start), and ImageIO makes the sizes.
 */
class ImageIoChosenLibvipsDisabledDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.with(ImageIoChosenLibvipsDisabledDbTest.class)
            .overrideConfigKey("quarkpress.media.image-processor", "imageio")
            .overrideConfigKey("quarkus.libvips.enabled", "false")
            .overrideConfigKey("quarkus.libvips.required", "true")
            .overrideConfigKey("quarkus.libvips.library", "/nonexistent/libvips.so.42");

    @Inject
    ImageProcessor processor;

    @Inject
    Media media;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void imageIoMakesTheSizesWithoutLibvips() throws Exception {
        assertThat(ClientProxy.unwrap(processor)).isInstanceOf(ImageIoProcessor.class);
        assertThat(Arc.container().instance(VipsImageProcessor.class).isAvailable())
                .isFalse();
        assertThat(Arc.container().instance(Libvips.class).isAvailable()).isFalse();
        MediaSnapshot m = Uploads.upload(
                media,
                Uploads.author(dataSource),
                Uploads.of("photo.jpg", MediaFixtures.jpeg(1200, 800), "image/jpeg"));
        assertThat(m.processing()).isEqualTo(Processing.DONE);
        assertThat(m.sizes().keySet()).containsExactlyInAnyOrder("medium", "large", "thumbnail", "medium_large");
        assertThat(m.sizes().get("medium").file()).isEqualTo("photo-300x200.jpg");
        assertThat(m.sizes().values()).extracting(MediaSize::mimeType).containsOnly("image/jpeg");
    }
}

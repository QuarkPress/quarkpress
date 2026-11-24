package org.quarkword.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.quarkword.media.MediaSize;
import org.quarkword.media.MediaSnapshot;
import org.quarkword.media.Processing;
import org.quarkword.media.image.imageio.runtime.ImageIoProcessor;
import org.quarkword.media.image.vips.runtime.VipsImageProcessor;
import org.quarkword.testing.wprestparity.MediaFixtures;
import org.sitenetsoft.quarkus.libvips.Libvips;

/**
 * Choosing ImageIO by name and switching quarkus-libvips off, as the module's README recommends: the application builds
 * and starts without loading libvips (it is required and its library path names no file, so a load would fail the
 * start), and ImageIO makes the sizes.
 */
class ImageIoChosenLibvipsDisabledDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.with(ImageIoChosenLibvipsDisabledDbTest.class)
            .overrideConfigKey("quarkword.media.image-processor", "imageio")
            .overrideConfigKey("quarkus.libvips.enabled", "false")
            .overrideConfigKey("quarkus.libvips.required", "true")
            .overrideConfigKey("quarkus.libvips.library", "/nonexistent/libvips.so.42");

    @Inject
    ImageProcessor processor;

    @Inject
    Media media;

    @Inject
    @DataSource("quarkword")
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

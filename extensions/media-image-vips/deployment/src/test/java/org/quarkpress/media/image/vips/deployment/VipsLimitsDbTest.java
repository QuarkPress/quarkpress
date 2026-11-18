package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.LibvipsConfig;

/**
 * libvips's input limits, when not configured, rise to QuarkPress's own: the largest upload (here the media module's
 * upload size, preferred to the HTTP body limit) and the most pixels an image may have to be decoded.
 */
class VipsLimitsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.with(VipsLimitsDbTest.class)
            .overrideConfigKey("quarkpress.media.max-upload-size", "300M")
            .overrideConfigKey("quarkus.http.limits.max-body-size", "200M")
            .overrideConfigKey("quarkpress.media.max-image-pixels", "400000000");

    @Inject
    LibvipsConfig libvips;

    @Test
    void theLimitsRiseToQuarkPresss() {
        assertThat(libvips.limits().maxInputSize().asLongValue()).isEqualTo(300L << 20);
        assertThat(libvips.limits().maxInputPixels()).isEqualTo(400_000_000L);
    }
}

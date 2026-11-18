package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.LibvipsConfig;

/**
 * Without a media upload size, libvips's unconfigured input size rises to the HTTP body limit; its pixel limit,
 * above QuarkPress's default, stays libvips's own.
 */
class VipsLimitsBodySizeDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            VipsApp.with(VipsLimitsBodySizeDbTest.class).overrideConfigKey("quarkus.http.limits.max-body-size", "200M");

    @Inject
    LibvipsConfig libvips;

    @Test
    void theSizeLimitRisesToTheBodyLimit() {
        assertThat(libvips.limits().maxInputSize().asLongValue()).isEqualTo(200L << 20);
        assertThat(libvips.limits().maxInputPixels()).isEqualTo(268_402_689L);
    }
}

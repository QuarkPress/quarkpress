package org.quarkword.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.libvips.LibvipsConfig;

/** libvips's input limits configured as 0 (none) are kept, and the application starts. */
class VipsLimitsOffDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.with(VipsLimitsOffDbTest.class)
            .overrideConfigKey("quarkword.media.max-upload-size", "10M")
            .overrideConfigKey("quarkus.libvips.limits.max-input-size", "0")
            .overrideConfigKey("quarkus.libvips.limits.max-input-pixels", "0");

    @Inject
    LibvipsConfig libvips;

    @Test
    void configuredLimitsAreKept() {
        assertThat(libvips.limits().maxInputSize().asLongValue()).isZero();
        assertThat(libvips.limits().maxInputPixels()).isZero();
    }
}

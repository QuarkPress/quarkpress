package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A configured libvips pixel limit below the most pixels QuarkPress decodes fails the start. */
class VipsPixelLimitTooLowDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.with(VipsPixelLimitTooLowDbTest.class)
            .overrideConfigKey("quarkus.libvips.limits.max-input-pixels", "1000")
            .assertException(failure -> assertThat(VipsApp.messages(failure))
                    .contains("quarkus.libvips.limits.max-input-pixels (1000) is below the most pixels QuarkPress"
                            + " decodes (100000000): raise it or set it to 0"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}

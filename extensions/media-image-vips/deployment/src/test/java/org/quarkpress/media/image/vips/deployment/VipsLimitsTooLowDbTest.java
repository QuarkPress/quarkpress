package org.quarkpress.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A configured libvips input size below the largest upload QuarkPress accepts fails the start. */
class VipsLimitsTooLowDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = VipsApp.with(VipsLimitsTooLowDbTest.class)
            .overrideConfigKey("quarkpress.media.max-upload-size", "10M")
            .overrideConfigKey("quarkus.libvips.limits.max-input-size", "1M")
            .assertException(failure -> assertThat(VipsApp.messages(failure))
                    .contains("quarkus.libvips.limits.max-input-size (1048576) is below the largest upload QuarkPress"
                            + " accepts (10485760): raise it or set it to 0"));

    @Test
    void theApplicationDoesNotStart() {
        // assertException above checks the failure
    }
}

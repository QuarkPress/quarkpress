package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** An image processor choice that no installed module answers to fails the build, naming those installed. */
class UnknownImageProcessorTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.buildOnly(TestStorage.class)
            .overrideConfigKey("quarkpress.media.image-processor", "vips")
            .assertException(e -> assertThat(Failures.messages(e))
                    .contains("java.lang.IllegalStateException: quarkpress.media.image-processor=vips names no"
                            + " installed image processor module; installed: none"));

    @Test
    void aNameNoModuleProducesFailsTheBuild() {
        // assertException above checks the failure
    }
}

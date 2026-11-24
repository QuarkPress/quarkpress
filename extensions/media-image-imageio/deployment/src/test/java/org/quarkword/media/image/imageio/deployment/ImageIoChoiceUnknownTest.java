package org.quarkword.media.image.imageio.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * With the ImageIO module installed, choosing an image processor no module provides fails the build, naming ImageIO.
 */
class ImageIoChoiceUnknownTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(ImageIoChoiceUnknownTest.class, TestStorage.class))
            .overrideConfigKey("quarkus.datasource.\"quarkword\".db-kind", "postgresql")
            .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
            .overrideConfigKey("quarkus.datasource.\"quarkword\".devservices.enabled", "false")
            .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
            .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
            .overrideConfigKey("quarkword.media.image-processor", "vips")
            .assertException(e -> assertThat(messages(e))
                    .contains("java.lang.IllegalStateException: quarkword.media.image-processor=vips names no"
                            + " installed image processor module; installed: imageio"));

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }

    /** The messages of a failure, its causes and its suppressed exceptions. */
    private static String messages(Throwable failure) {
        StringBuilder text = new StringBuilder();
        append(failure, text, 0);
        return text.toString();
    }

    private static void append(Throwable failure, StringBuilder text, int depth) {
        if (failure == null || depth > 20) {
            return;
        }
        text.append(failure.getClass().getName())
                .append(": ")
                .append(failure.getMessage())
                .append('\n');
        for (Throwable suppressed : failure.getSuppressed()) {
            append(suppressed, text, depth + 1);
        }
        append(failure.getCause(), text, depth + 1);
    }
}

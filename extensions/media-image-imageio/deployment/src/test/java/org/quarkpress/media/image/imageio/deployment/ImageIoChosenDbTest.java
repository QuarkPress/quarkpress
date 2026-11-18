package org.quarkpress.media.image.imageio.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.image.imageio.runtime.ImageIoProcessor;

/** Choosing the ImageIO processor by name builds, and ImageIO is the image processor. */
class ImageIoChosenDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(ImageIoChosenDbTest.class, TestStorage.class))
            .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", kind())
            .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
            .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
            .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.http.test-ssl-port", "0")
            .overrideConfigKey("quarkpress.media.image-processor", "imageio");

    @Inject
    ImageProcessor processor;

    @Test
    void theChosenProcessorIsImageIo() {
        assertThat(processor).isInstanceOf(ImageIoProcessor.class);
    }

    /**
     * The database the test uses: {@code -DtestDb=postgresql} (default) or {@code mysql}, passed by Maven as the
     * environment variable {@code QUARKPRESS_TEST_DB}. A copy of media's test helper
     * {@code org.quarkpress.media.deployment.TestDb.kind()}, which this module cannot see: keep the two in step.
     */
    private static String kind() {
        String kind = System.getenv("QUARKPRESS_TEST_DB");
        if (kind == null || kind.isBlank()) {
            kind = "postgresql";
        }
        if (!kind.equals("postgresql") && !kind.equals("mysql")) {
            throw new IllegalStateException("QUARKPRESS_TEST_DB must be postgresql or mysql, not " + kind);
        }
        return kind;
    }
}

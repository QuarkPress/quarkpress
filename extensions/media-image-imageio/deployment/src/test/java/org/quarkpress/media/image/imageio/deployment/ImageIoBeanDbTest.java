package org.quarkpress.media.image.imageio.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.image.imageio.runtime.ImageIoProcessor;

/**
 * With the ImageIO module next to the media module, ImageIO is the image processor, with the configured pixel limit;
 * the media module's own processor, which handles nothing, steps aside.
 */
class ImageIoBeanDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(ImageIoBeanDbTest.class, TestStorage.class))
            .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", kind())
            .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
            .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
            .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.http.test-ssl-port", "0")
            .overrideConfigKey("quarkpress.media.max-image-pixels", "12");

    @Inject
    ImageProcessor processor;

    @Test
    void theInjectedProcessorIsImageIo() {
        assertThat(processor).isInstanceOf(ImageIoProcessor.class);
        assertThat(processor.supports("image/jpeg")).isTrue();
        assertThat(((ImageIoProcessor) processor).maxPixels()).isEqualTo(12);
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

package org.quarkword.media.image.imageio.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.media.ImageProcessor;
import org.quarkword.media.image.imageio.runtime.ImageIoProcessor;

/**
 * With the ImageIO module next to the media module, ImageIO is the image processor, with the configured pixel limit;
 * the media module's own processor, which handles nothing, steps aside.
 */
class ImageIoBeanDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(ImageIoBeanDbTest.class, TestStorage.class))
            .overrideConfigKey("quarkus.datasource.\"quarkword\".db-kind", kind())
            .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
            .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
            .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.http.test-ssl-port", "0")
            .overrideConfigKey("quarkword.media.max-image-pixels", "12");

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
     * environment variable {@code QUARKWORD_TEST_DB}. A copy of media's test helper
     * {@code org.quarkword.media.deployment.TestDb.kind()}, which this module cannot see: keep the two in step.
     */
    private static String kind() {
        String kind = System.getenv("QUARKWORD_TEST_DB");
        if (kind == null || kind.isBlank()) {
            kind = "postgresql";
        }
        if (!kind.equals("postgresql") && !kind.equals("mysql")) {
            throw new IllegalStateException("QUARKWORD_TEST_DB must be postgresql or mysql, not " + kind);
        }
        return kind;
    }
}

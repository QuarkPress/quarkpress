package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.runtime.NoImageProcessor;

/**
 * Without an image processor module the media module has its own processor, which handles no type: images are stored
 * as WordPress stores them without an image editor, without generated sizes.
 */
class NoImageProcessorDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.with(NoImageProcessorDbTest.class);

    @Inject
    ImageProcessor processor;

    @Test
    void theMediaModuleAloneHasTheProcessorThatHandlesNothing() {
        assertThat(processor).isInstanceOf(NoImageProcessor.class);
    }

    @Test
    void itSupportsNoTypeReadsNothingAndDecodesNothing() throws Exception {
        for (String type : List.of("image/jpeg", "image/png", "image/gif", "image/bmp", "image/webp", "text/plain")) {
            assertThat(processor.supports(type)).as(type).isFalse();
        }
        Path dir = Files.createDirectories(Path.of("target", "no-image-processor"));
        Path file =
                Files.write(dir.resolve("image.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        assertThat(processor.read(file)).isEmpty();
        assertThatThrownBy(() -> processor.decode(file, "image/png"))
                .isInstanceOf(ImageProcessingException.class)
                .hasMessage("No image processor is installed.");
    }
}

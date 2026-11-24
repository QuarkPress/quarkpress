package org.quarkword.media.image.vips.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.runtime.BlockingOperationNotAllowedException;
import io.quarkus.test.QuarkusExtensionTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.media.ImageProcessor;
import org.quarkword.testing.wprestparity.MediaFixtures;

/** Decoding on an event loop fails loudly, as a blocking call there, never as an image that cannot be decoded. */
class EventLoopDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            VipsApp.with(EventLoopDbTest.class).overrideConfigKey("quarkus.libvips.required", "true");

    @Inject
    ImageProcessor processor;

    @Inject
    Vertx vertx;

    @Test
    void aDecodeOnAnEventLoopFailsLoudly() throws Exception {
        Path jpeg =
                Files.write(Files.createTempFile(Path.of("target"), "event-loop-", ".tmp"), MediaFixtures.jpeg(64, 48));
        CompletableFuture<Throwable> thrown = new CompletableFuture<>();
        vertx.getOrCreateContext().runOnContext(v -> {
            try {
                processor.decode(jpeg, "image/jpeg").close();
                thrown.complete(null);
            } catch (Throwable failure) {
                thrown.complete(failure);
            }
        });
        assertThat(thrown.get(30, TimeUnit.SECONDS)).isInstanceOf(BlockingOperationNotAllowedException.class);
    }
}

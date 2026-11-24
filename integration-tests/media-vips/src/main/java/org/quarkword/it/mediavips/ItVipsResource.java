package org.quarkword.it.mediavips;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.ClientProxy;
import io.smallrye.common.annotation.NonBlocking;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.nio.file.Files;
import org.quarkword.media.DecodedImage;
import org.quarkword.media.ImageProcessor;
import org.quarkword.media.image.vips.runtime.VipsImageProcessor;
import org.sitenetsoft.quarkus.libvips.Libvips;

/**
 * Test scaffolding, not a product API: what the tests of the libvips image processor read from inside the application
 * under test, over HTTP, so that they also run against the packaged application.
 */
@Path("/it/vips")
@Produces(MediaType.APPLICATION_JSON)
public class ItVipsResource {

    @Inject
    Libvips libvips;

    @Inject
    ImageProcessor processor;

    @Inject
    Instance<VipsImageProcessor> vips;

    /** {@code open}, the libvips images open now; {@code leaked}, those released without being closed. */
    @GET
    @Path("/open-images")
    public ObjectNode openImages() {
        return JsonNodeFactory.instance
                .objectNode()
                .put("open", libvips.openImages())
                .put("leaked", libvips.leakedImages());
    }

    /**
     * {@code processor}, the class of the media module's image processor; {@code writable}, the types libvips writes
     * here; {@code readable}, the types it reads without writing them (HEIC and HEIF when an HEVC decoder is
     * installed); both empty when the media module's processor is not libvips's (it is chosen away).
     */
    @GET
    @Path("/formats")
    public ObjectNode formats() {
        ObjectNode answer = JsonNodeFactory.instance.objectNode();
        answer.put("processor", ClientProxy.unwrap(processor).getClass().getName());
        ArrayNode writable = answer.putArray("writable");
        ArrayNode readable = answer.putArray("readable");
        if (vips.isResolvable()) {
            vips.get().formats().writable().stream().sorted().forEach(writable::add);
            vips.get().formats().readable().stream().sorted().forEach(readable::add);
        }
        return answer;
    }

    /**
     * Decodes the posted JPEG with the image processor on the event loop, where blocking is not allowed: answers
     * {@code thrown}, the class of what the decode threw (null when it did not), and {@code message}.
     */
    @POST
    @Path("/event-loop")
    @NonBlocking
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public ObjectNode decodeOnTheEventLoop(byte[] jpeg) throws IOException {
        java.nio.file.Path file = Files.createTempFile("it-vips-event-loop", ".tmp");
        ObjectNode answer = JsonNodeFactory.instance.objectNode();
        try {
            Files.write(file, jpeg);
            try (DecodedImage decoded = processor.decode(file, "image/jpeg")) {
                answer.putNull("thrown");
                answer.put("width", decoded.width());
            } catch (Throwable thrown) {
                answer.put("thrown", thrown.getClass().getName());
                answer.put("message", thrown.getMessage());
            }
            return answer;
        } finally {
            Files.deleteIfExists(file);
        }
    }
}

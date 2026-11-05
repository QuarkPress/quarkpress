package org.quarkpress.media.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.util.Optional;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.media.ImageProcessor;

/**
 * The image processor when no image processor module is installed: it handles no type, so images are stored as
 * WordPress stores them without an image editor, with their dimensions and metadata and without generated sizes. An
 * image processor module's bean replaces it.
 */
@DefaultBean
@ApplicationScoped
public class NoImageProcessor implements ImageProcessor {

    @Override
    public boolean supports(String mimeType) {
        return false;
    }

    @Override
    public Optional<ImageInfo> read(Path file) {
        return Optional.empty();
    }

    @Override
    public DecodedImage decode(Path file, String mimeType) throws ImageProcessingException {
        throw new ImageProcessingException("No image processor is installed.");
    }
}

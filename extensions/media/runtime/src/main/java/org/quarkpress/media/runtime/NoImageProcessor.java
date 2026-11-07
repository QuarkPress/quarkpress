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
 * The image processor when no image processor module is installed: it handles no type. An upload that declares an
 * image type other than SVG, HEIC or HEIF is therefore refused as one no editor can make sizes for, as WordPress without
 * an image editor refuses it, unless it asks for no sizes or unprocessable images are accepted; those images, and
 * images that declare no type, are stored with their dimensions and metadata and without generated sizes. An image
 * processor module's bean replaces it.
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

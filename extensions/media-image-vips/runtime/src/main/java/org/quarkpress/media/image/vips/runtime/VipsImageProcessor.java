package org.quarkpress.media.image.vips.runtime;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.runtime.ImageHeaders;
import org.quarkpress.media.runtime.MediaConfig;
import org.sitenetsoft.quarkus.libvips.Access;
import org.sitenetsoft.quarkus.libvips.FailOn;
import org.sitenetsoft.quarkus.libvips.Image;
import org.sitenetsoft.quarkus.libvips.Intent;
import org.sitenetsoft.quarkus.libvips.Libvips;
import org.sitenetsoft.quarkus.libvips.LibvipsException;
import org.sitenetsoft.quarkus.libvips.LimitExceededException;
import org.sitenetsoft.quarkus.libvips.LoadOptions;
import org.sitenetsoft.quarkus.libvips.SaveOptions;

/**
 * The image processor of libvips: makes sub-sizes in JPEG, PNG, WebP, AVIF and GIF, reads HEIC and HEIF (whose sizes
 * are written in another type), and reads the header of TIFF files without making sizes, as far as the installed
 * libvips can; the types it handles are found by trying each at start-up. Images are never turned on decoding, and
 * copies keep the colour profile (none in PNG copies) and no other metadata.
 */
@ApplicationScoped
public class VipsImageProcessor implements ImageProcessor {

    private static final Logger LOG = Logger.getLogger(VipsImageProcessor.class);

    /** Only the header is read; nothing is decoded. */
    private static final LoadOptions HEADER = new LoadOptions(Access.SEQUENTIAL, Optional.empty(), 0, 1);

    /**
     * The first page or frame of a JPEG, decoded whole on first use, as far as its data goes: a JPEG whose data stops
     * early still gives sizes, as it does in WordPress.
     */
    private static final LoadOptions DECODE_LENIENT = new LoadOptions(Access.RANDOM, Optional.of(FailOn.NONE), 0, 1);

    /**
     * The first page or frame of any other type, decoded whole on first use; a truncated file fails, and is kept
     * without sizes, as WordPress keeps a truncated PNG or GIF.
     */
    private static final LoadOptions DECODE_STRICT =
            new LoadOptions(Access.RANDOM, Optional.of(FailOn.TRUNCATED), 0, 1);

    private static final String DECODE_FAILED = "libvips could not decode the image.";

    private final Libvips libvips;
    private final long maxPixels;
    private volatile VipsFormats formats;

    @Inject
    VipsImageProcessor(Libvips libvips, MediaConfig config) {
        this(libvips, config.maxImagePixels(), VipsFormats.NONE);
    }

    /**
     * A processor of the types {@code formats} names, refusing to decode images of more than {@code maxPixels}
     * pixels.
     */
    public VipsImageProcessor(Libvips libvips, long maxPixels, VipsFormats formats) {
        this.libvips = libvips;
        this.maxPixels = maxPixels;
        this.formats = formats;
    }

    /** Finds the types the installed libvips handles, once, at start-up. */
    void probe(@Observes StartupEvent event) {
        if (libvips.isAvailable()) {
            formats = VipsFormats.probe(libvips);
            LOG.info(formats.describe(libvips.version()));
        }
    }

    /** The types this processor handles. */
    public VipsFormats formats() {
        return formats;
    }

    /** Whether images of {@code mimeType} are written, read, or accepted without sizes. */
    @Override
    public boolean supports(String mimeType) {
        return formats.supports(mimeType);
    }

    /** Whether copies are written as {@code mimeType}: never HEIC, HEIF or TIFF, which are only read. */
    @Override
    public boolean writes(String mimeType) {
        return formats.writes(mimeType);
    }

    /**
     * The type and stored dimensions of {@code file} from its header, when it is of a type this processor handles. A
     * header declaring more pixels than libvips loads is measured by the media module's own header reader.
     */
    @Override
    public Optional<ImageInfo> read(Path file) {
        try (Image image = libvips.load(file, HEADER)) {
            Optional<String> type = VipsTypes.mimeOf(image).filter(formats::supports);
            if (type.isEmpty() || image.width() <= 0 || image.height() <= 0) {
                return Optional.empty();
            }
            return Optional.of(new ImageInfo(type.get(), image.width(), image.height()));
        } catch (LimitExceededException tooLarge) {
            return headerOf(file).filter(info -> formats.supports(info.mimeType()));
        } catch (LibvipsException | IllegalArgumentException failure) {
            LOG.debugf("libvips could not read an image header: %s", failure.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Decodes the first page or frame of {@code file}. Its real type, told by its content, must be one this processor
     * decodes, and its pixels at most the limit; CMYK images are converted to sRGB. A JPEG whose data stops early is
     * decoded as far as it goes; any other file whose data stops early, and any damaged file, fails here, not when a
     * copy is written.
     */
    @Override
    public DecodedImage decode(Path file, String mimeType) throws ImageProcessingException {
        VipsFormats known = formats;
        if (!known.decodes(mimeType)) {
            throw new ImageProcessingException("libvips does not read " + mimeType + ".");
        }
        Image image;
        try {
            image = libvips.load(file, lenient(mimeType) ? DECODE_LENIENT : DECODE_STRICT);
        } catch (LimitExceededException tooLarge) {
            throw tooLarge(file, tooLarge);
        } catch (LibvipsException failure) {
            throw decodeFailed(failure);
        } catch (IllegalArgumentException failure) {
            // the message may name the file
            LOG.debugf("libvips refused to open an image file: %s", failure.getMessage());
            throw new ImageProcessingException(DECODE_FAILED);
        }
        try {
            Optional<String> real = VipsTypes.mimeOf(image).filter(known::decodes);
            if (real.isEmpty() || lenient(real.get()) != lenient(mimeType)) {
                // not a type decoded here, or loaded as leniently as another type would be
                throw new ImageProcessingException(DECODE_FAILED);
            }
            int width = image.width();
            int height = image.height();
            if ((long) width * height > maxPixels) {
                throw new ImageProcessingException(
                        "The image is too large to process: " + width + "x" + height + " pixels.");
            }
            decodeEveryPixel(image);
            Image decoded = image;
            if (image.interpretation().equals("cmyk")) {
                decoded = image.iccTransform("srgb", Intent.RELATIVE);
                image.close();
            }
            return new VipsImage(decoded, known);
        } catch (ImageProcessingException failure) {
            image.close();
            throw failure;
        } catch (LibvipsException failure) {
            image.close();
            throw decodeFailed(failure);
        } catch (IllegalArgumentException failure) {
            // an argument libvips refuses for this image; its text may repeat the arguments
            image.close();
            LOG.debugf("libvips refused to decode an image: %s", failure.getMessage());
            throw new ImageProcessingException(DECODE_FAILED);
        } catch (RuntimeException | Error failure) {
            image.close();
            throw failure;
        }
    }

    /** Whether images of {@code mimeType} are decoded as far as their data goes when it stops early. */
    private static boolean lenient(String mimeType) {
        return mimeType.equals("image/jpeg");
    }

    /** libvips's largest shrink factor. */
    static final int MAX_SHRINK = 1_000_000;

    /**
     * Reads every pixel now, by averaging the whole image down to a few pixels: a random-access load decodes the image
     * once, on its first use, so this costs no second decode, and damage that the type's strictness refuses fails the
     * decode rather than a later write. Reading only the last pixel is not enough: libvips 8.15 then reports a
     * truncated JPEG or PNG as a warning only. Each factor is at most libvips's largest, which still leaves at most 10
     * pixels a side for the largest image libvips loads.
     */
    private static void decodeEveryPixel(Image image) {
        double hshrink = Math.min(image.width(), MAX_SHRINK);
        double vshrink = Math.min(image.height(), MAX_SHRINK);
        try (Image pixel = image.call("shrink", Map.of("hshrink", hshrink, "vshrink", vshrink))) {
            pixel.save(SaveOptions.png());
        }
    }

    private static ImageProcessingException decodeFailed(LibvipsException failure) {
        LOG.debugf("libvips could not decode an image: %s: %s", failure.getMessage(), failure.detail());
        return new ImageProcessingException(DECODE_FAILED, failure);
    }

    /** Above one of libvips's own input limits: the dimensions come from the media module's header reader. */
    private static ImageProcessingException tooLarge(Path file, LimitExceededException failure) {
        LOG.debugf("libvips refused an image above its limits: %s", failure.getMessage());
        return headerOf(file)
                .map(info -> new ImageProcessingException(
                        "The image is too large to process: " + info.width() + "x" + info.height() + " pixels."))
                .orElseGet(() -> new ImageProcessingException("The image is too large to process."));
    }

    private static Optional<ImageInfo> headerOf(Path file) {
        try {
            return ImageHeaders.read(file);
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }
}

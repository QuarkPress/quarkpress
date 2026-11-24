package org.quarkword.media.image.vips.runtime;

import io.quarkus.arc.ClientProxy;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ConfigurationException;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.quarkword.media.DecodedImage;
import org.quarkword.media.ImageInfo;
import org.quarkword.media.ImageProcessingException;
import org.quarkword.media.ImageProcessor;
import org.quarkword.media.runtime.ImageHeaders;
import org.quarkword.media.runtime.MediaConfig;
import org.sitenetsoft.quarkus.libvips.Access;
import org.sitenetsoft.quarkus.libvips.FailOn;
import org.sitenetsoft.quarkus.libvips.Image;
import org.sitenetsoft.quarkus.libvips.Intent;
import org.sitenetsoft.quarkus.libvips.Libvips;
import org.sitenetsoft.quarkus.libvips.LibvipsConfig;
import org.sitenetsoft.quarkus.libvips.LibvipsException;
import org.sitenetsoft.quarkus.libvips.LimitExceededException;
import org.sitenetsoft.quarkus.libvips.LoadOptions;
import org.sitenetsoft.quarkus.libvips.SaveOptions;

/**
 * The image processor of libvips: makes sub-sizes in JPEG, PNG, WebP, AVIF and GIF, reads HEIC and HEIF (whose sizes
 * are written in another type), and reads the header of TIFF files without making sizes, as far as the installed
 * libvips can; the types it handles are found by trying each at start-up. Images are never turned on decoding, and
 * copies keep the colour profile (none in PNG copies) and no other metadata.
 *
 * <p>It takes precedence over any other image processor bean. The other one, when there is one (ImageIO's), handles
 * the types libvips does not, and every type when libvips is not available; a type libvips decodes is never handed to
 * it, even when libvips fails on the file. At start-up, libvips's input limits are checked against the largest upload
 * and the pixel limit QuarkWord accepts.
 */
@Alternative
@Priority(10)
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
    private final Optional<BeanManager> beans;
    private final Optional<LibvipsConfig> libvipsConfig;
    private volatile VipsFormats formats;
    private volatile Optional<ImageProcessor> fallback = Optional.empty();

    @Inject
    VipsImageProcessor(Libvips libvips, MediaConfig config, LibvipsConfig libvipsConfig, BeanManager beans) {
        this.libvips = libvips;
        this.maxPixels = config.maxImagePixels();
        this.beans = Optional.of(beans);
        this.libvipsConfig = Optional.of(libvipsConfig);
        this.formats = VipsFormats.NONE;
    }

    /**
     * A processor of the types {@code formats} names, refusing to decode images of more than {@code maxPixels}
     * pixels, without a fallback.
     */
    public VipsImageProcessor(Libvips libvips, long maxPixels, VipsFormats formats) {
        this.libvips = libvips;
        this.maxPixels = maxPixels;
        this.beans = Optional.empty();
        this.libvipsConfig = Optional.empty();
        this.formats = formats;
    }

    /**
     * At start-up, before the application's own start-up work (such as taking up uploads left pending): checks
     * libvips's input limits, finds the fallback, and the types the installed libvips handles; says in one line what
     * processes images.
     */
    void start(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent event) {
        libvipsConfig.ifPresent(this::checkLimits);
        fallback = beans.flatMap(Fallback::of);
        Optional<String> other =
                fallback.map(p -> ClientProxy.unwrap(p).getClass().getSimpleName());
        if (!libvips.isAvailable()) {
            formats = VipsFormats.NONE;
            LOG.debugf("libvips: %s", libvips.status().error().orElse("not available"));
            LOG.warn(other.map(name -> "libvips is not available; images are processed by " + name + " instead")
                    .orElse("libvips is not available; images get no generated sizes"));
            return;
        }
        formats = VipsFormats.probe(libvips);
        LOG.info(formats.describe(libvips.version())
                + other.map(name -> "; other types by " + name).orElse(""));
    }

    /**
     * Fails the start when a configured libvips input limit would refuse an image QuarkWord accepts: unconfigured
     * limits are raised by {@link VipsLimitDefaults}.
     */
    private void checkLimits(LibvipsConfig config) {
        Config all = ConfigProvider.getConfig();
        long accepted = VipsLimitDefaults.acceptedUploadSize(
                key -> all.getOptionalValue(key, String.class).filter(text -> !text.isBlank()));
        Optional<String> problem = VipsLimitDefaults.check(
                config.limits().maxInputSize().asLongValue(), config.limits().maxInputPixels(), accepted, maxPixels);
        if (problem.isPresent()) {
            throw new ConfigurationException(problem.get());
        }
    }

    /**
     * The other image processor, which handles the types libvips does not; found at start-up, empty before and when
     * there is none.
     */
    public Optional<ImageProcessor> fallback() {
        return fallback;
    }

    /** The types libvips handles; none before start-up and when libvips is not available. */
    public VipsFormats formats() {
        return formats;
    }

    /**
     * Whether images of {@code mimeType} are written, read, or accepted without sizes, by libvips or the fallback.
     */
    @Override
    public boolean supports(String mimeType) {
        return formats.supports(mimeType)
                || fallback.map(other -> other.supports(mimeType)).orElse(false);
    }

    /**
     * Whether copies are written as {@code mimeType}: while libvips is available, only the types libvips writes (never
     * HEIC, HEIF or TIFF, which are only read), so that copies of an image the fallback decodes are written in JPEG
     * when libvips does not write its type; without libvips, the fallback's answer.
     */
    @Override
    public boolean writes(String mimeType) {
        if (libvips.isAvailable()) {
            return formats.writes(mimeType);
        }
        return fallback.map(other -> other.writes(mimeType)).orElse(false);
    }

    /**
     * The type and stored dimensions of {@code file} from its header, when it is of a type libvips handles; else as
     * the fallback reads it. A header declaring more pixels than libvips loads is measured by the media module's own
     * header reader.
     */
    @Override
    public Optional<ImageInfo> read(Path file) {
        Optional<ImageInfo> own = libvips.isAvailable() ? readHeader(file) : Optional.empty();
        if (own.isPresent()) {
            return own;
        }
        return fallback.flatMap(other -> other.read(file));
    }

    private Optional<ImageInfo> readHeader(Path file) {
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
     * Decodes the first page or frame of {@code file}: with libvips when it decodes {@code mimeType}, else with the
     * fallback when that handles it. With libvips, the real type of the file, told by its content, must be one it
     * decodes, and its pixels at most the limit; CMYK images are converted to sRGB. A JPEG whose data stops early is
     * decoded as far as it goes; any other file whose data stops early, and any damaged file, fails here, not when a
     * copy is written. A failure of libvips is never retried with the fallback.
     */
    @Override
    public DecodedImage decode(Path file, String mimeType) throws ImageProcessingException {
        VipsFormats known = formats;
        if (known.decodes(mimeType)) {
            return decodeWithLibvips(file, mimeType, known);
        }
        Optional<ImageProcessor> other = fallback.filter(p -> p.supports(mimeType));
        if (other.isPresent()) {
            return other.get().decode(file, mimeType);
        }
        throw new ImageProcessingException("No image processor reads " + mimeType + ".");
    }

    private DecodedImage decodeWithLibvips(Path file, String mimeType, VipsFormats known)
            throws ImageProcessingException {
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

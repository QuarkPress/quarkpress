package org.quarkpress.media.image.imageio.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.runtime.MediaConfig;
import org.w3c.dom.Node;

/**
 * The image processor of ImageIO and the TwelveMonkeys plugins: reads and writes JPEG, PNG, GIF and BMP (WebP and
 * TIFF are read only). Sub-sizes keep the source's ICC colour profile, with the pixels left in its colour space, and
 * nothing else of its metadata; JPEG is written baseline at the asked quality; an animated GIF gives its first frame.
 */
@ApplicationScoped
public class ImageIoProcessor implements ImageProcessor {

    private static final Set<String> SUPPORTED = Set.of("image/jpeg", "image/png", "image/gif", "image/bmp");

    private final long maxPixels;

    @Inject
    ImageIoProcessor(MediaConfig config) {
        this(config.maxImagePixels());
    }

    /** A processor that refuses to decode images of more than {@code maxPixels} pixels. */
    public ImageIoProcessor(long maxPixels) {
        this.maxPixels = maxPixels;
    }

    /** The most pixels an image may have to be decoded. */
    public long maxPixels() {
        return maxPixels;
    }

    @Override
    public boolean supports(String mimeType) {
        return SUPPORTED.contains(mimeType);
    }

    @Override
    public Optional<ImageInfo> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                return Optional.empty();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                String type = readType(reader.getFormatName());
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return type == null || width <= 0 || height <= 0
                        ? Optional.empty()
                        : Optional.of(new ImageInfo(type, width, height));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Decodes the first image of {@code file}. The reader is chosen by the file's content; {@code mimeType} is
     * informational only and never selects or overrides it. The dimensions are checked against the pixel limit before
     * any pixel is read. An RGB or grey image with an ICC profile keeps its samples unconverted, in that profile's
     * colour space; any other image (CMYK, for one) is converted to sRGB without a profile. A GIF gives its first frame
     * drawn at its place on a transparent canvas of the GIF's logical screen, the dimensions its header declares.
     */
    @Override
    public DecodedImage decode(Path file, String mimeType) throws ImageProcessingException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                throw new ImageProcessingException("The image file cannot be opened.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new ImageProcessingException("No image reader knows the file's type.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, false);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                int[] screen = format.equals("gif") ? gifScreen(reader, width, height) : null;
                if (screen != null) {
                    requireWithinLimit(screen[0], screen[1]);
                }
                requireWithinLimit(width, height);
                boolean jpeg = format.equals("jpeg") || format.equals("jpg");
                ImageReadParam param = reader.getDefaultReadParam();
                Optional<ICC_Profile> profile =
                        IccProfiles.of(reader.getImageMetadata(0)).filter(IccProfiles::keepable);
                if (profile.isPresent()) {
                    Optional<ImageTypeSpecifier> raw = typeOf(reader, profile.get());
                    if (raw.isPresent()) {
                        // a reader that would convert to sRGB is asked for the samples in the profile's space
                        param.setDestinationType(raw.get());
                    } else if (!format.equals("png")) {
                        // the reader may convert the colours, so its profile no longer describes them; the JDK's
                        // PNG reader never applies a profile
                        profile = Optional.empty();
                    }
                }
                List<String> damage = new ArrayList<>();
                reader.addIIOReadWarningListener((source, warning) -> {
                    if (isDamage(warning)) {
                        damage.add(warning);
                    }
                });
                BufferedImage image = reader.read(0, param);
                if (!damage.isEmpty()) {
                    throw new ImageProcessingException("The image could not be decoded: " + damage.get(0));
                }
                ImageIoImage decoded = ImageIoImage.of(image, profile, !jpeg);
                if (screen != null
                        && (screen[0] != decoded.width()
                                || screen[1] != decoded.height()
                                || screen[2] != 0
                                || screen[3] != 0)) {
                    ImageIoImage placed = decoded.placedOn(screen[0], screen[1], screen[2], screen[3]);
                    decoded.close();
                    return placed;
                }
                return decoded;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new ImageProcessingException(withCause("The image could not be decoded", e), e);
        }
    }

    /**
     * {@code sentence} with the cause's own message after a colon, or with a full stop when it has none; never the
     * cause's class name.
     */
    static String withCause(String sentence, Throwable cause) {
        String message = cause.getMessage();
        return message == null || message.isBlank() ? sentence + "." : sentence + ": " + message.strip();
    }

    private void requireWithinLimit(int width, int height) throws ImageProcessingException {
        if ((long) width * height > maxPixels) {
            throw new ImageProcessingException(
                    "The image is too large to process: " + width + "x" + height + " pixels.");
        }
    }

    /**
     * A GIF's logical screen and the place of its first frame on it: width, height, left, top. The screen is the
     * frame's own size when the header declares none.
     */
    private static int[] gifScreen(ImageReader reader, int frameWidth, int frameHeight) throws IOException {
        int screenWidth = 0;
        int screenHeight = 0;
        IIOMetadata stream = reader.getStreamMetadata();
        if (stream != null && stream.getNativeMetadataFormatName() != null) {
            Node node = first(stream.getAsTree(stream.getNativeMetadataFormatName()), "LogicalScreenDescriptor");
            screenWidth = attribute(node, "logicalScreenWidth");
            screenHeight = attribute(node, "logicalScreenHeight");
        }
        int left = 0;
        int top = 0;
        IIOMetadata image = reader.getImageMetadata(0);
        if (image != null && image.getNativeMetadataFormatName() != null) {
            Node node = first(image.getAsTree(image.getNativeMetadataFormatName()), "ImageDescriptor");
            left = attribute(node, "imageLeftPosition");
            top = attribute(node, "imageTopPosition");
        }
        if (screenWidth <= 0 || screenHeight <= 0) {
            return new int[] {frameWidth, frameHeight, 0, 0};
        }
        return new int[] {screenWidth, screenHeight, left, top};
    }

    private static Node first(Node tree, String name) {
        return tree instanceof IIOMetadataNode root
                        && root.getElementsByTagName(name).getLength() > 0
                ? root.getElementsByTagName(name).item(0)
                : null;
    }

    /** The non-negative integer attribute {@code name} of {@code node}; 0 when absent or not a number. */
    private static int attribute(Node node, String name) {
        if (!(node instanceof IIOMetadataNode element)) {
            return 0;
        }
        String value = element.getAttribute(name);
        int result = 0;
        if (value == null || value.isEmpty() || value.length() > 9) {
            return 0;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
            result = result * 10 + (c - '0');
        }
        return result;
    }

    /**
     * The write parameters of a JPEG: explicit compression at {@code quality} (1 to 100), never progressive.
     */
    public static ImageWriteParam jpegParam(ImageWriter writer, int quality) {
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality / 100f);
        param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
        return param;
    }

    /** The ImageIO format this processor writes {@code mimeType} as; null for a type it does not write. */
    static String writeFormat(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> "jpeg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            default -> null;
        };
    }

    /** The MIME type of a reader's format name; null for a format media has no type for. */
    private static String readType(String formatName) {
        return switch (formatName.toLowerCase(Locale.ROOT)) {
            case "jpeg", "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "tif", "tiff" -> "image/tiff";
            default -> null;
        };
    }

    /** The reader's image type whose colour space is {@code profile}: its samples as stored, unconverted. */
    private static Optional<ImageTypeSpecifier> typeOf(ImageReader reader, ICC_Profile profile) throws IOException {
        Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
        while (types.hasNext()) {
            ImageTypeSpecifier type = types.next();
            if (type.getColorModel().getColorSpace() instanceof ICC_ColorSpace space
                    && IccProfiles.sameColours(space.getProfile(), profile)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** Whether a reader's warning says the file ends early or its data is cut, rather than a harmless oddity. */
    private static boolean isDamage(String warning) {
        String text = warning == null ? "" : warning.toLowerCase(Locale.ROOT);
        return text.contains("premature end") || text.contains("truncated") || text.contains("missing eoi");
    }
}

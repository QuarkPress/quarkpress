package org.quarkpress.media.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.Encoded;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessingException;
import org.quarkpress.media.ImageProcessor;

/**
 * An image processor on the JDK's own ImageIO codecs that handles JPEG, PNG and GIF only, the types WordPress's GD
 * editor handles without WebP and AVIF support. Switches let a test make one output size fail to encode or make
 * decoding throw.
 */
@ApplicationScoped
public class TestProcessor implements ImageProcessor {

    private static final Map<String, String> FORMATS =
            Map.of("image/jpeg", "jpeg", "image/png", "png", "image/gif", "gif");

    private final Set<String> failingSizes = ConcurrentHashMap.newKeySet();
    private volatile RuntimeException decodeFailure;

    /** Encoding an image of {@code width}&times;{@code height} fails with {@link ImageProcessingException}. */
    public void failSize(int width, int height) {
        failingSizes.add(width + "x" + height);
    }

    /** Decoding throws {@code failure}; null to decode again. */
    public void throwOnDecode(RuntimeException failure) {
        decodeFailure = failure;
    }

    /** Resets the switches. */
    public void reset() {
        failingSizes.clear();
        decodeFailure = null;
    }

    @Override
    public boolean supports(String mimeType) {
        return FORMATS.containsKey(mimeType);
    }

    @Override
    public Optional<ImageInfo> read(Path file) {
        try (InputStream in = Files.newInputStream(file);
                ImageInputStream stream = ImageIO.createImageInputStream(in)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String type = mimeType(reader.getFormatName());
                if (type == null) {
                    return Optional.empty();
                }
                return Optional.of(new ImageInfo(type, reader.getWidth(0), reader.getHeight(0)));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    @Override
    public DecodedImage decode(Path file, String mimeType) throws ImageProcessingException {
        RuntimeException failure = decodeFailure;
        if (failure != null) {
            throw failure;
        }
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                throw new ImageProcessingException("No image reader knows the file's type.");
            }
            return new Image(image);
        } catch (IOException unreadable) {
            throw new ImageProcessingException("The image could not be decoded.");
        }
    }

    private static String mimeType(String formatName) {
        return switch (formatName.toLowerCase(java.util.Locale.ROOT)) {
            case "jpeg", "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> null;
        };
    }

    private final class Image implements DecodedImage {

        private final BufferedImage pixels;

        Image(BufferedImage pixels) {
            this.pixels = pixels;
        }

        @Override
        public int width() {
            return pixels.getWidth();
        }

        @Override
        public int height() {
            return pixels.getHeight();
        }

        @Override
        public DecodedImage oriented(int exifOrientation) {
            if (exifOrientation < 2 || exifOrientation > 8) {
                return this;
            }
            int w = pixels.getWidth();
            int h = pixels.getHeight();
            boolean swap = exifOrientation >= 5;
            BufferedImage target = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < target.getHeight(); y++) {
                for (int x = 0; x < target.getWidth(); x++) {
                    int[] from = switch (exifOrientation) {
                        case 2 -> new int[] {w - 1 - x, y};
                        case 3 -> new int[] {w - 1 - x, h - 1 - y};
                        case 4 -> new int[] {x, h - 1 - y};
                        case 5 -> new int[] {y, x};
                        case 6 -> new int[] {y, h - 1 - x};
                        case 7 -> new int[] {w - 1 - y, h - 1 - x};
                        default -> new int[] {w - 1 - y, x};
                    };
                    target.setRGB(x, y, pixels.getRGB(from[0], from[1]));
                }
            }
            return new Image(target);
        }

        @Override
        public DecodedImage region(int x, int y, int w, int h, int targetWidth, int targetHeight) {
            BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = target.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(pixels, 0, 0, targetWidth, targetHeight, x, y, x + w, y + h, null);
            } finally {
                g.dispose();
            }
            return new Image(target);
        }

        @Override
        public Encoded encode(Path target, String mimeType, int quality) throws ImageProcessingException {
            String format = FORMATS.get(mimeType);
            if (format == null) {
                throw new ImageProcessingException("The image cannot be written as " + mimeType + ".");
            }
            if (failingSizes.contains(pixels.getWidth() + "x" + pixels.getHeight())) {
                throw new ImageProcessingException("The image could not be written.");
            }
            BufferedImage image = pixels;
            if (format.equals("jpeg") && pixels.getColorModel().hasAlpha()) {
                image = new BufferedImage(pixels.getWidth(), pixels.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                try {
                    g.drawImage(pixels, 0, 0, null);
                } finally {
                    g.dispose();
                }
            }
            ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
            try (ImageOutputStream out = ImageIO.createImageOutputStream(target.toFile())) {
                writer.setOutput(out);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (format.equals("jpeg")) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(Math.max(1, Math.min(100, quality)) / 100f);
                }
                writer.write(null, new IIOImage(image, null, null), param);
            } catch (IOException failure) {
                throw new ImageProcessingException("The image could not be written.");
            } finally {
                writer.dispose();
            }
            try {
                return new Encoded(mimeType, image.getWidth(), image.getHeight(), Files.size(target));
            } catch (IOException failure) {
                throw new ImageProcessingException("The image could not be written.");
            }
        }

        @Override
        public void close() {
            pixels.flush();
        }
    }
}

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * editor handles without WebP and AVIF support. Switches let a test make one output size fail to encode, make one cut
 * throw, make decoding throw (always or once), slow it down or hold it until a gate opens; counters tell how often it
 * decoded and how many decoded images were open at once (an image and every image made from it count as one until all
 * of them are closed).
 */
@ApplicationScoped
public class TestProcessor implements ImageProcessor {

    private static final Map<String, String> FORMATS =
            Map.of("image/jpeg", "jpeg", "image/png", "png", "image/gif", "gif");

    private final Set<String> failingSizes = ConcurrentHashMap.newKeySet();
    private final Set<String> failingRegions = ConcurrentHashMap.newKeySet();
    private volatile RuntimeException decodeFailure;
    private volatile Error decodeError;
    private final AtomicReference<Error> nextDecodeError = new AtomicReference<>();
    private volatile Throwable turnFailure;
    private volatile Throwable supportsFailure;
    private volatile long decodeMillis;
    private final AtomicInteger decodes = new AtomicInteger();
    private final AtomicInteger encodes = new AtomicInteger();
    private volatile CyclicBarrier encodeBarrier;
    private final AtomicInteger barrierParties = new AtomicInteger();
    private final AtomicReference<Runnable> duringDecode = new AtomicReference<>();
    private volatile CountDownLatch decodeGate;
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger maxOpen = new AtomicInteger();

    /** Encoding an image of {@code width}&times;{@code height} fails with {@link ImageProcessingException}. */
    public void failSize(int width, int height) {
        failingSizes.add(width + "x" + height);
    }

    /** Cutting a region scaled to {@code width}&times;{@code height} throws {@link IllegalStateException}. */
    public void failRegion(int width, int height) {
        failingRegions.add(width + "x" + height);
    }

    /** Decoding throws {@code failure}; null to decode again. */
    public void throwOnDecode(RuntimeException failure) {
        decodeFailure = failure;
    }

    /** Decoding throws {@code error} (such as an {@link OutOfMemoryError}); null to decode again. */
    public void errorOnDecode(Error error) {
        decodeError = error;
    }

    /** The next decode alone throws {@code error}. */
    public void errorOnNextDecode(Error error) {
        nextDecodeError.set(error);
    }

    /** Turning an image upright throws {@code failure}, a runtime exception or an error; null to turn again. */
    public void failTurns(Throwable failure) {
        turnFailure = failure;
    }

    /** {@link #supports} throws {@code failure}; null to answer again. */
    public void throwOnSupports(RuntimeException failure) {
        supportsFailure = failure;
    }

    /** {@link #supports} throws {@code error} (such as a {@link StackOverflowError}); null to answer again. */
    public void errorOnSupports(Error error) {
        supportsFailure = error;
    }

    /** Each decode takes at least {@code millis} milliseconds longer. */
    public void slowDecodes(long millis) {
        decodeMillis = millis;
    }

    /** How many times an image was encoded since the last {@link #reset}. */
    public int encodes() {
        return encodes.get();
    }

    /**
     * The first {@code parties} encodes wait for each other (at most ten seconds) before they write: concurrent runs
     * then reach their saves together.
     */
    public void encodeBarrier(int parties) {
        barrierParties.set(parties);
        encodeBarrier = new CyclicBarrier(parties);
    }

    /**
     * The next decode first runs {@code work} once, on its own thread, and waits for it; then it decodes (or fails as
     * the switches say, as they are once {@code work} has ended).
     */
    public void duringNextDecode(Runnable work) {
        duringDecode.set(work);
    }

    /** Each decode first waits (at most a minute) until {@code gate} is open; null to decode at once. */
    public void holdDecodes(CountDownLatch gate) {
        decodeGate = gate;
    }

    /** How many times {@link #decode} was called since the last {@link #reset}. */
    public int decodes() {
        return decodes.get();
    }

    /** The highest number of decoded images open at once since the last {@link #reset}. */
    public int maxOpenDecodes() {
        return maxOpen.get();
    }

    /** Resets the switches and the counters. */
    public void reset() {
        failingSizes.clear();
        failingRegions.clear();
        decodeFailure = null;
        decodeError = null;
        nextDecodeError.set(null);
        turnFailure = null;
        supportsFailure = null;
        decodeMillis = 0;
        decodes.set(0);
        encodes.set(0);
        encodeBarrier = null;
        barrierParties.set(0);
        duringDecode.set(null);
        decodeGate = null;
        maxOpen.set(open.get());
    }

    @Override
    public boolean supports(String mimeType) {
        Throwable failure = supportsFailure;
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
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
        decodes.incrementAndGet();
        CountDownLatch gate = decodeGate;
        if (gate != null) {
            try {
                gate.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        Runnable work = duringDecode.getAndSet(null);
        if (work != null) {
            Thread helper = new Thread(work, "test-during-decode");
            helper.start();
            try {
                helper.join(60_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        RuntimeException failure = decodeFailure;
        if (failure != null) {
            throw failure;
        }
        Error error = decodeError;
        if (error != null) {
            throw error;
        }
        Error once = nextDecodeError.getAndSet(null);
        if (once != null) {
            throw once;
        }
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                throw new ImageProcessingException("No image reader knows the file's type.");
            }
            Root root = new Root();
            Image decoded = new Image(image, root);
            long millis = decodeMillis;
            if (millis > 0) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return decoded;
        } catch (IOException unreadable) {
            throw new ImageProcessingException("The image could not be decoded.");
        }
    }

    private static String mimeType(String formatName) {
        return switch (formatName.toLowerCase(Locale.ROOT)) {
            case "jpeg", "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> null;
        };
    }

    /** A decoded image and the images made from it: open while any of them is. */
    private final class Root {
        private final AtomicInteger live = new AtomicInteger();

        void opened() {
            if (live.getAndIncrement() == 0) {
                int now = open.incrementAndGet();
                maxOpen.accumulateAndGet(now, Math::max);
            }
        }

        void closed() {
            if (live.decrementAndGet() == 0) {
                open.decrementAndGet();
            }
        }
    }

    private final class Image implements DecodedImage {

        private final BufferedImage pixels;
        private final Root root;
        private boolean closed;

        Image(BufferedImage pixels, Root root) {
            this.pixels = pixels;
            this.root = root;
            root.opened();
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
            Throwable failure = turnFailure;
            if (failure instanceof RuntimeException runtime && exifOrientation >= 2 && exifOrientation <= 8) {
                throw runtime;
            }
            if (failure instanceof Error error && exifOrientation >= 2 && exifOrientation <= 8) {
                throw error;
            }
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
            return new Image(target, root);
        }

        @Override
        public DecodedImage region(int x, int y, int w, int h, int targetWidth, int targetHeight) {
            if (failingRegions.contains(targetWidth + "x" + targetHeight)) {
                throw new IllegalStateException("the test processor cannot cut this region");
            }
            BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = target.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(pixels, 0, 0, targetWidth, targetHeight, x, y, x + w, y + h, null);
            } finally {
                g.dispose();
            }
            return new Image(target, root);
        }

        @Override
        public Encoded encode(Path target, String mimeType, int quality) throws ImageProcessingException {
            String format = FORMATS.get(mimeType);
            if (format == null) {
                throw new ImageProcessingException("The image cannot be written as " + mimeType + ".");
            }
            encodes.incrementAndGet();
            CyclicBarrier barrier = encodeBarrier;
            if (barrier != null && barrierParties.getAndDecrement() > 0) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception broken) {
                    throw new IllegalStateException("the encode barrier broke", broken);
                }
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
        public synchronized void close() {
            if (!closed) {
                closed = true;
                pixels.flush();
                root.closed();
            }
        }
    }
}

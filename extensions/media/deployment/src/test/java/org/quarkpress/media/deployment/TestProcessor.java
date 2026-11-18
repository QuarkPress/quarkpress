package org.quarkpress.media.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.quarkpress.media.runtime.ImageHeaders;

/**
 * An image processor on the JDK's own ImageIO codecs that handles JPEG, PNG and GIF only, the types WordPress's GD
 * editor handles without WebP and AVIF support. Switches let a test make one output size fail to encode, make one cut
 * throw, make decoding throw (always or once), slow it down or hold it until a gate opens; counters tell how often it
 * decoded and how many decoded images were open at once (an image and every image made from it count as one until all
 * of them are closed). Every encode is recorded with the type, quality and lossless mode it was asked for. Switched to
 * extra formats, it also supports WebP and HEIC: it reads a WebP's dimensions from its header and every HEIC as
 * 4032&times;3024, and stands in for their pixels with images that hold none (their copies are placeholder bytes).
 */
@ApplicationScoped
public class TestProcessor implements ImageProcessor {

    private static final Map<String, String> FORMATS =
            Map.of("image/jpeg", "jpeg", "image/png", "png", "image/gif", "gif");

    /** The types the extra formats switch adds. */
    private static final Set<String> EXTRA = Set.of("image/webp", "image/heic");

    /** The dimensions this processor reads for every HEIC file once the extra formats are on. */
    public static final int HEIC_WIDTH = 4032;

    public static final int HEIC_HEIGHT = 3024;

    /** One encode: the image's size and what it was asked to be written as. */
    public record Encode(int width, int height, String mimeType, int quality, boolean lossless) {}

    private final List<Encode> encoded = new CopyOnWriteArrayList<>();
    private volatile boolean extraFormats;
    private volatile RuntimeException readFailure;

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

    /** Whether WebP and HEIC are supported too (see the class description). */
    public void extraFormats(boolean on) {
        extraFormats = on;
    }

    /** {@link #read} throws {@code failure}; null to read again. */
    public void throwOnRead(RuntimeException failure) {
        readFailure = failure;
    }

    /** Every encode since the last {@link #reset}, in order. */
    public List<Encode> encoded() {
        return List.copyOf(encoded);
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
        encoded.clear();
        extraFormats = false;
        readFailure = null;
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
        return FORMATS.containsKey(mimeType) || (extraFormats && EXTRA.contains(mimeType));
    }

    @Override
    public Optional<ImageInfo> read(Path file) {
        RuntimeException failure = readFailure;
        if (failure != null) {
            throw failure;
        }
        if (extraFormats) {
            Optional<ImageInfo> extra = readExtra(file);
            if (extra.isPresent()) {
                return extra;
            }
        }
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
        if (extraFormats && EXTRA.contains(mimeType)) {
            ImageInfo info = readExtra(file)
                    .filter(found -> found.mimeType().equals(mimeType))
                    .orElseThrow(() -> new ImageProcessingException("The image could not be decoded."));
            return new Placeholder(info.width(), info.height(), new Root());
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

    /** A WebP's header dimensions, or the fixed size of a HEIC (an ISO file whose brand is {@code heic}). */
    private static Optional<ImageInfo> readExtra(Path file) {
        try {
            byte[] head;
            try (InputStream in = Files.newInputStream(file)) {
                head = in.readNBytes(12);
            }
            String brand = head.length == 12 ? new String(head, 4, 8, StandardCharsets.US_ASCII) : "";
            if (brand.equals("ftypheic")) {
                return Optional.of(new ImageInfo("image/heic", HEIC_WIDTH, HEIC_HEIGHT));
            }
            return ImageHeaders.read(file).filter(info -> info.mimeType().equals("image/webp"));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    /** Counts an encode, waits at the encode barrier and fails a failing size, as every encode does. */
    private void encoding(int width, int height, String mimeType, int quality, boolean lossless)
            throws ImageProcessingException {
        encodes.incrementAndGet();
        encoded.add(new Encode(width, height, mimeType, quality, lossless));
        CyclicBarrier barrier = encodeBarrier;
        if (barrier != null && barrierParties.getAndDecrement() > 0) {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception broken) {
                throw new IllegalStateException("the encode barrier broke", broken);
            }
        }
        if (failingSizes.contains(width + "x" + height)) {
            throw new ImageProcessingException("The image could not be written.");
        }
    }

    private boolean writable(String mimeType) {
        return FORMATS.containsKey(mimeType) || (extraFormats && EXTRA.contains(mimeType));
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
            return encode(target, mimeType, quality, false);
        }

        @Override
        public Encoded encode(Path target, String mimeType, int quality, boolean lossless)
                throws ImageProcessingException {
            String format = FORMATS.get(mimeType);
            if (format == null) {
                if (writable(mimeType)) {
                    return placeholder(target, mimeType, pixels.getWidth(), pixels.getHeight(), quality, lossless);
                }
                throw new ImageProcessingException("The image cannot be written as " + mimeType + ".");
            }
            encoding(pixels.getWidth(), pixels.getHeight(), mimeType, quality, lossless);
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

    /** Writes placeholder bytes for a copy of {@code width}&times;{@code height} in {@code mimeType}. */
    private Encoded placeholder(Path target, String mimeType, int width, int height, int quality, boolean lossless)
            throws ImageProcessingException {
        encoding(width, height, mimeType, quality, lossless);
        try {
            Files.writeString(target, "placeholder " + mimeType + " " + width + "x" + height);
            return new Encoded(mimeType, width, height, Files.size(target));
        } catch (IOException failure) {
            throw new ImageProcessingException("The image could not be written.");
        }
    }

    /** An image of the extra formats: its size alone, no pixels. */
    private final class Placeholder implements DecodedImage {

        private final int width;
        private final int height;
        private final Root root;
        private boolean closed;

        Placeholder(int width, int height, Root root) {
            this.width = width;
            this.height = height;
            this.root = root;
            root.opened();
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public DecodedImage oriented(int exifOrientation) {
            if (exifOrientation < 2 || exifOrientation > 8) {
                return this;
            }
            boolean swap = exifOrientation >= 5;
            return new Placeholder(swap ? height : width, swap ? width : height, root);
        }

        @Override
        public DecodedImage region(int x, int y, int w, int h, int targetWidth, int targetHeight) {
            if (failingRegions.contains(targetWidth + "x" + targetHeight)) {
                throw new IllegalStateException("the test processor cannot cut this region");
            }
            return new Placeholder(targetWidth, targetHeight, root);
        }

        @Override
        public Encoded encode(Path target, String mimeType, int quality) throws ImageProcessingException {
            return encode(target, mimeType, quality, false);
        }

        @Override
        public Encoded encode(Path target, String mimeType, int quality, boolean lossless)
                throws ImageProcessingException {
            if (!writable(mimeType)) {
                throw new ImageProcessingException("The image cannot be written as " + mimeType + ".");
            }
            return placeholder(target, mimeType, width, height, quality, lossless);
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                root.closed();
            }
        }
    }
}

package org.quarkpress.media.image.imageio.runtime;

import java.awt.Point;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.Encoded;
import org.quarkpress.media.ImageProcessingException;

/**
 * A decoded image as 8-bit interleaved samples: grey or RGB, with or without alpha, in the colour space of
 * {@link #profile} when there is one (the samples as the source stored them, never converted) and sRGB (or plain grey)
 * otherwise. Every operation makes a new image; scaling and turning work on the samples alone, so a kept profile still
 * describes the written pixels.
 */
final class ImageIoImage implements DecodedImage {

    private static final int[][] BAND_OFFSETS = {{0}, {0, 1}, {0, 1, 2}, {0, 1, 2, 3}};

    private final int width;
    private final int height;
    private final int bands;
    private final boolean alpha;
    private final Optional<ICC_Profile> profile;
    private byte[] samples;

    private ImageIoImage(
            int width, int height, int bands, boolean alpha, Optional<ICC_Profile> profile, byte[] samples) {
        this.width = width;
        this.height = height;
        this.bands = bands;
        this.alpha = alpha;
        this.profile = profile;
        this.samples = samples;
    }

    /**
     * The samples of {@code image}. With {@code profile}, the image's samples are taken as they are, in that
     * profile's colour space (the profile must match the image: RGB with three colour components, grey with one);
     * without, an sRGB or grey image's samples are taken as they are and any other colour space (CMYK, for one) is
     * converted to sRGB. {@code keepAlpha} false drops an alpha channel.
     */
    static ImageIoImage of(BufferedImage image, Optional<ICC_Profile> profile, boolean keepAlpha)
            throws ImageProcessingException {
        ColorModel model = image.getColorModel();
        if (model.isAlphaPremultiplied()) {
            image.coerceData(false);
            model = image.getColorModel();
        }
        int type = model.getColorSpace().getType();
        boolean integral = image.getRaster().getTransferType() != DataBuffer.TYPE_FLOAT
                && image.getRaster().getTransferType() != DataBuffer.TYPE_DOUBLE;
        boolean indexed = model instanceof IndexColorModel;
        boolean grey = !indexed && type == ColorSpace.TYPE_GRAY && model.getNumColorComponents() == 1;
        boolean rgb = indexed || (type == ColorSpace.TYPE_RGB && model.getNumColorComponents() == 3);
        boolean withAlpha = keepAlpha && model.hasAlpha();
        if (profile.isPresent()) {
            int profileType = profile.get().getColorSpaceType();
            boolean matches = integral
                    && ((profileType == ColorSpace.TYPE_GRAY && grey) || (profileType == ColorSpace.TYPE_RGB && rgb));
            if (!matches) {
                profile = Optional.empty();
            }
        }
        if (profile.isEmpty() && rgb && !indexed && !model.getColorSpace().isCS_sRGB()) {
            rgb = false; // an RGB colour space other than sRGB without a profile to keep: converted below
        }
        if (!integral) {
            grey = false;
            rgb = false;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int outBands = (grey ? 1 : 3) + (withAlpha ? 1 : 0);
        byte[] out = allocate(w, h, outBands);
        Raster raster = image.getRaster();
        if (indexed) {
            IndexColorModel palette = (IndexColorModel) model;
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                raster.getSamples(0, y, w, 1, 0, row);
                int o = y * w * outBands;
                for (int x = 0; x < w; x++) {
                    int index = row[x];
                    out[o++] = (byte) palette.getRed(index);
                    out[o++] = (byte) palette.getGreen(index);
                    out[o++] = (byte) palette.getBlue(index);
                    if (withAlpha) {
                        out[o++] = (byte) palette.getAlpha(index);
                    }
                }
            }
        } else if (grey || rgb) {
            int colours = grey ? 1 : 3;
            int inBands = raster.getNumBands();
            int[] max = new int[inBands];
            for (int b = 0; b < inBands; b++) {
                max[b] = (1 << model.getComponentSize(b)) - 1;
            }
            int[] row = new int[w * inBands];
            for (int y = 0; y < h; y++) {
                raster.getPixels(0, y, w, 1, row);
                int o = y * w * outBands;
                for (int x = 0; x < w; x++) {
                    int i = x * inBands;
                    for (int b = 0; b < colours; b++) {
                        out[o++] = (byte) to8(row[i + b], max[b]);
                    }
                    if (withAlpha) {
                        out[o++] = (byte) to8(row[i + colours], max[colours]);
                    }
                }
            }
        } else {
            // Neither grey nor RGB as stored (CMYK, YCCK, a floating-point raster...): converted to sRGB.
            profile = Optional.empty();
            int[] argb = new int[w];
            for (int y = 0; y < h; y++) {
                image.getRGB(0, y, w, 1, argb, 0, w);
                int o = y * w * outBands;
                for (int x = 0; x < w; x++) {
                    int p = argb[x];
                    out[o++] = (byte) (p >> 16);
                    out[o++] = (byte) (p >> 8);
                    out[o++] = (byte) p;
                    if (withAlpha) {
                        out[o++] = (byte) (p >>> 24);
                    }
                }
            }
        }
        return new ImageIoImage(w, h, outBands, withAlpha, profile, out);
    }

    /**
     * This image drawn at ({@code left}, {@code top}) onto a fully transparent canvas of {@code canvasWidth} by
     * {@code canvasHeight}, the parts outside the canvas cut off: a GIF frame placed on its logical screen.
     */
    ImageIoImage placedOn(int canvasWidth, int canvasHeight, int left, int top) throws ImageProcessingException {
        byte[] source = samples();
        int colours = colours();
        int outBands = colours + 1;
        byte[] out = allocate(canvasWidth, canvasHeight, outBands);
        int fromX = Math.max(0, -left);
        int toX = Math.min(width, canvasWidth - left);
        int fromY = Math.max(0, -top);
        int toY = Math.min(height, canvasHeight - top);
        for (int y = fromY; y < toY; y++) {
            for (int x = fromX; x < toX; x++) {
                int i = (y * width + x) * bands;
                int o = ((y + top) * canvasWidth + x + left) * outBands;
                System.arraycopy(source, i, out, o, colours);
                out[o + colours] = alpha ? source[i + colours] : (byte) 0xFF;
            }
        }
        return new ImageIoImage(canvasWidth, canvasHeight, outBands, true, profile, out);
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
        byte[] source = samples();
        boolean swap = exifOrientation >= 5;
        int w = swap ? height : width;
        int h = swap ? width : height;
        byte[] out = new byte[source.length];
        int o = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // (sx, sy): the source pixel shown at (x, y) once the image is upright
                int sx;
                int sy;
                switch (exifOrientation) {
                    case 2 -> { // mirrored
                        sx = width - 1 - x;
                        sy = y;
                    }
                    case 3 -> { // rotated 180 degrees
                        sx = width - 1 - x;
                        sy = height - 1 - y;
                    }
                    case 4 -> { // flipped
                        sx = x;
                        sy = height - 1 - y;
                    }
                    case 5 -> { // transposed
                        sx = y;
                        sy = x;
                    }
                    case 6 -> { // turned 90 degrees clockwise
                        sx = y;
                        sy = height - 1 - x;
                    }
                    case 7 -> { // transversed
                        sx = width - 1 - y;
                        sy = height - 1 - x;
                    }
                    default -> { // turned 90 degrees counter-clockwise
                        sx = width - 1 - y;
                        sy = x;
                    }
                }
                System.arraycopy(source, (sy * width + sx) * bands, out, o, bands);
                o += bands;
            }
        }
        return new ImageIoImage(w, h, bands, alpha, profile, out);
    }

    @Override
    public DecodedImage region(int x, int y, int w, int h, int targetWidth, int targetHeight) {
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || (long) x + w > width || (long) y + h > height) {
            throw new IllegalArgumentException("the region " + w + "x" + h + " at " + x + "," + y
                    + " is not inside the image of " + width + "x" + height);
        }
        if (targetWidth <= 0 || targetHeight <= 0) {
            throw new IllegalArgumentException("target dimensions are positive: " + targetWidth + "x" + targetHeight);
        }
        byte[] source = samples();
        byte[] cut = allocateUnchecked(w, h, bands);
        int rowLength = w * bands;
        for (int row = 0; row < h; row++) {
            System.arraycopy(source, ((y + row) * width + x) * bands, cut, row * rowLength, rowLength);
        }
        int cw = w;
        int ch = h;
        // Halved, each direction on its own, while more than twice the target, then one step to the target.
        while (cw > 2 * targetWidth || ch > 2 * targetHeight) {
            int nw = cw > 2 * targetWidth ? cw / 2 : cw;
            int nh = ch > 2 * targetHeight ? ch / 2 : ch;
            cut = bilinear(cut, cw, ch, bands, alpha, nw, nh);
            cw = nw;
            ch = nh;
        }
        if (cw != targetWidth || ch != targetHeight) {
            cut = bilinear(cut, cw, ch, bands, alpha, targetWidth, targetHeight);
        }
        return new ImageIoImage(targetWidth, targetHeight, bands, alpha, profile, cut);
    }

    @Override
    public Encoded encode(Path target, String mimeType, int quality) throws ImageProcessingException {
        String format = ImageIoProcessor.writeFormat(mimeType);
        if (format == null) {
            throw new ImageProcessingException("The image cannot be written as " + mimeType + ".");
        }
        try {
            switch (format) {
                case "jpeg" -> writeJpeg(target, Math.max(1, Math.min(100, quality)));
                case "png" -> writePng(target);
                case "gif" -> write(target, "gif", srgb(true, true));
                default -> write(target, "bmp", srgb(false, false));
            }
            return new Encoded(mimeType, width, height, Files.size(target));
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // the write's failure is the one reported
            }
            throw new ImageProcessingException(ImageIoProcessor.withCause("The image could not be written", e), e);
        }
    }

    @Override
    public void close() {
        samples = null;
    }

    private byte[] samples() {
        byte[] current = samples;
        if (current == null) {
            throw new IllegalStateException("the image is closed");
        }
        return current;
    }

    private int colours() {
        return bands - (alpha ? 1 : 0);
    }

    /**
     * A JPEG of the colour samples (without alpha). No metadata is handed to the writer, so it writes its own JFIF
     * segment and, for an image in a kept profile's colour space, that profile as the APP2 segment; nothing of the
     * source's metadata. (Handed explicit metadata, the JDK's writer drops a grey image's profile.)
     */
    private void writeJpeg(Path target, int quality) throws IOException {
        BufferedImage image = image(false);
        ImageWriter writer = writer("jpeg");
        try {
            write(target, writer, new IIOImage(image, null, null), ImageIoProcessor.jpegParam(writer, quality));
        } finally {
            writer.dispose();
        }
    }

    /** A PNG of the samples, with alpha when there is some and an {@code iCCP} chunk for the kept profile. */
    private void writePng(Path target) throws IOException {
        BufferedImage image = image(alpha);
        ImageWriter writer = writer("png");
        try {
            IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), null);
            if (profile.isPresent()) {
                String format = metadata.getNativeMetadataFormatName();
                IIOMetadataNode root = new IIOMetadataNode(format);
                IIOMetadataNode iccp = new IIOMetadataNode(IccProfiles.PNG_NODE);
                iccp.setAttribute("profileName", "ICC profile");
                iccp.setAttribute("compressionMethod", "deflate");
                // the JDK's writer stores the user object as the chunk's data, which is zlib-compressed
                iccp.setUserObject(IccProfiles.deflate(profile.get().getData()));
                root.appendChild(iccp);
                metadata.mergeTree(format, root);
            }
            write(target, writer, new IIOImage(image, null, metadata), null);
        } finally {
            writer.dispose();
        }
    }

    private static void write(Path target, String format, BufferedImage image) throws IOException {
        ImageWriter writer = writer(format);
        try {
            write(target, writer, new IIOImage(image, null, null), null);
        } finally {
            writer.dispose();
        }
    }

    private static void write(Path target, ImageWriter writer, IIOImage image, ImageWriteParam param)
            throws IOException {
        try (OutputStream file = Files.newOutputStream(target);
                ImageOutputStream out = new MemoryCacheImageOutputStream(file)) {
            writer.setOutput(out);
            writer.write(null, image, param);
            out.flush();
        }
    }

    private static ImageWriter writer(String format) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            throw new IOException("no ImageIO writer for " + format);
        }
        return writers.next();
    }

    /**
     * The samples as an image in their own colour space (the kept profile's, or sRGB, or plain grey), alpha dropped
     * unless {@code withAlpha}; nothing is converted.
     */
    private BufferedImage image(boolean withAlpha) {
        byte[] source = samples();
        int colours = colours();
        boolean keepAlpha = withAlpha && alpha;
        int outBands = colours + (keepAlpha ? 1 : 0);
        byte[] data = outBands == bands ? source : dropAlpha(source, colours);
        ColorSpace space = profile.<ColorSpace>map(ICC_ColorSpace::new)
                .orElseGet(() -> ColorSpace.getInstance(colours == 1 ? ColorSpace.CS_GRAY : ColorSpace.CS_sRGB));
        ComponentColorModel model = new ComponentColorModel(
                space,
                keepAlpha,
                false,
                keepAlpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE,
                DataBuffer.TYPE_BYTE);
        WritableRaster raster = Raster.createInterleavedRaster(
                new DataBufferByte(data, data.length),
                width,
                height,
                width * outBands,
                outBands,
                BAND_OFFSETS[outBands - 1],
                new Point(0, 0));
        return new BufferedImage(model, raster, false, null);
    }

    private byte[] dropAlpha(byte[] source, int colours) {
        byte[] out = allocateUnchecked(width, height, colours);
        int o = 0;
        for (int i = 0; i < source.length; i += bands) {
            for (int b = 0; b < colours; b++) {
                out[o++] = source[i + b];
            }
        }
        return out;
    }

    /**
     * The image in sRGB for a format that carries no profile: grey samples repeated as RGB, the samples of an sRGB
     * image as they are, a kept profile's samples converted from it. With {@code withAlpha} the alpha is kept, as
     * fully transparent or opaque when {@code binaryAlpha} (GIF has one transparent colour); without, it is dropped.
     */
    private BufferedImage srgb(boolean withAlpha, boolean binaryAlpha) {
        boolean keepAlpha = withAlpha && alpha;
        BufferedImage out =
                new BufferedImage(width, height, keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        boolean convert = profile.isPresent() && !isSrgb(profile.get());
        BufferedImage source = convert ? image(keepAlpha) : null;
        byte[] s = samples();
        int colours = colours();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            if (convert) {
                source.getRGB(0, y, width, 1, row, 0, width);
            } else {
                int i = y * width * bands;
                for (int x = 0; x < width; x++, i += bands) {
                    int r = s[i] & 0xFF;
                    int g = colours == 1 ? r : s[i + 1] & 0xFF;
                    int b = colours == 1 ? r : s[i + 2] & 0xFF;
                    int a = alpha ? s[i + colours] & 0xFF : 0xFF;
                    row[x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            if (keepAlpha && binaryAlpha) {
                for (int x = 0; x < width; x++) {
                    row[x] = (row[x] >>> 24) < 128 ? 0 : row[x] | 0xFF000000;
                }
            }
            out.setRGB(0, y, width, 1, row, 0, width);
        }
        return out;
    }

    /**
     * The {@code w}&times;{@code h} interleaved samples scaled to {@code nw}&times;{@code nh} bilinearly, pixel centres
     * mapped onto pixel centres (halving averages each 2&times;2 block). With alpha, the colours are weighted by it, so
     * transparent pixels lend no colour to their neighbours.
     */
    private static byte[] bilinear(byte[] in, int w, int h, int bands, boolean alpha, int nw, int nh) {
        byte[] out = allocateUnchecked(nw, nh, bands);
        int[] x0 = new int[nw];
        int[] x1 = new int[nw];
        int[] fx = new int[nw];
        axis(w, nw, x0, x1, fx);
        int[] y0 = new int[nh];
        int[] y1 = new int[nh];
        int[] fy = new int[nh];
        axis(h, nh, y0, y1, fy);
        int colours = bands - (alpha ? 1 : 0);
        int o = 0;
        for (int y = 0; y < nh; y++) {
            int top = y0[y] * w;
            int bottom = y1[y] * w;
            int wy = fy[y];
            for (int x = 0; x < nw; x++) {
                int wx = fx[x];
                int i00 = (top + x0[x]) * bands;
                int i01 = (top + x1[x]) * bands;
                int i10 = (bottom + x0[x]) * bands;
                int i11 = (bottom + x1[x]) * bands;
                // weights in 1/65536: (1 - fx)(1 - fy), fx(1 - fy), (1 - fx)fy, fx fy
                long w00 = (long) (256 - wx) * (256 - wy);
                long w01 = (long) wx * (256 - wy);
                long w10 = (long) (256 - wx) * wy;
                long w11 = (long) wx * wy;
                if (alpha) {
                    long a00 = w00 * (in[i00 + colours] & 0xFF);
                    long a01 = w01 * (in[i01 + colours] & 0xFF);
                    long a10 = w10 * (in[i10 + colours] & 0xFF);
                    long a11 = w11 * (in[i11 + colours] & 0xFF);
                    long weight = a00 + a01 + a10 + a11;
                    for (int b = 0; b < colours; b++) {
                        long sum = a00 * (in[i00 + b] & 0xFF)
                                + a01 * (in[i01 + b] & 0xFF)
                                + a10 * (in[i10 + b] & 0xFF)
                                + a11 * (in[i11 + b] & 0xFF);
                        out[o++] = (byte) (weight == 0 ? 0 : (sum + weight / 2) / weight);
                    }
                    out[o++] = (byte) ((weight + 32768) >> 16);
                } else {
                    for (int b = 0; b < bands; b++) {
                        long sum = w00 * (in[i00 + b] & 0xFF)
                                + w01 * (in[i01 + b] & 0xFF)
                                + w10 * (in[i10 + b] & 0xFF)
                                + w11 * (in[i11 + b] & 0xFF);
                        out[o++] = (byte) ((sum + 32768) >> 16);
                    }
                }
            }
        }
        return out;
    }

    /** For each of the {@code n} output positions along an axis of {@code length}: the two inputs and the weight. */
    private static void axis(int length, int n, int[] first, int[] second, int[] weight) {
        double scale = (double) length / n;
        for (int i = 0; i < n; i++) {
            double at = (i + 0.5) * scale - 0.5;
            if (at < 0) {
                at = 0;
            }
            int lower = Math.min((int) at, length - 1);
            first[i] = lower;
            second[i] = Math.min(lower + 1, length - 1);
            weight[i] = (int) Math.round((at - lower) * 256);
        }
    }

    private static boolean isSrgb(ICC_Profile profile) {
        return IccProfiles.sameColours(profile, ICC_Profile.getInstance(ColorSpace.CS_sRGB));
    }

    private static int to8(int value, int max) {
        return max == 255 ? value : (int) (((long) value * 255 + max / 2) / max);
    }

    private static byte[] allocate(int w, int h, int bands) throws ImageProcessingException {
        long length = (long) w * h * bands;
        if (length > Integer.MAX_VALUE - 8) {
            throw new ImageProcessingException("The image is too large to process: " + w + "x" + h + " pixels.");
        }
        return new byte[(int) length];
    }

    private static byte[] allocateUnchecked(int w, int h, int bands) {
        return new byte[Math.multiplyExact(Math.multiplyExact(w, h), bands)];
    }
}

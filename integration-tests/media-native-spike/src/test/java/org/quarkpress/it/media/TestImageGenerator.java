package org.quarkpress.it.media;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Writes the committed test images under {@code src/test/resources/images} with the JDK only (no external tools).
 * Run from the module directory: {@code java src/test/java/org/quarkpress/it/media/TestImageGenerator.java
 * src/test/resources/images}. The tests never run it; the images are committed.
 */
public final class TestImageGenerator {

    static final String CAPTION = "QuarkPress spike caption";

    private TestImageGenerator() {}

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/test/resources/images");
        Files.createDirectories(dir);
        Files.write(dir.resolve("photo.jpg"), photoJpeg());
        Files.write(dir.resolve("cmyk.jpg"), cmykJpeg());
        Files.write(dir.resolve("image.png"), png());
        Files.write(dir.resolve("image.gif"), gif());
        Files.write(dir.resolve("image.webp"), losslessWebp());
    }

    /** 64x48 RGB JPEG with an EXIF orientation of 6 (rotate 90° clockwise) and an IPTC caption. */
    static byte[] photoJpeg() throws IOException {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.RED, 64, 48, Color.BLUE));
        g.fillRect(0, 0, 64, 48);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        byte[] jpeg = out.toByteArray();
        // SOI, then the JFIF APP0 segment the JDK writes; the EXIF and IPTC segments go right after it.
        int app0Length = ((jpeg[4] & 0xff) << 8) | (jpeg[5] & 0xff);
        int insertAt = 4 + app0Length;
        ByteArrayOutputStream spliced = new ByteArrayOutputStream();
        spliced.write(jpeg, 0, insertAt);
        spliced.write(segment(0xE1, exifOrientation(6)));
        spliced.write(segment(0xED, photoshopIptcCaption(CAPTION)));
        spliced.write(jpeg, insertAt, jpeg.length - insertAt);
        return spliced.toByteArray();
    }

    private static byte[] segment(int marker, byte[] payload) {
        int length = payload.length + 2;
        byte[] segment = new byte[payload.length + 4];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) marker;
        segment[2] = (byte) (length >> 8);
        segment[3] = (byte) length;
        System.arraycopy(payload, 0, segment, 4, payload.length);
        return segment;
    }

    /** "Exif\0\0" and a big-endian TIFF structure whose IFD0 holds only Orientation (0x0112, SHORT). */
    private static byte[] exifOrientation(int orientation) {
        return new byte[] {
            'E',
            'x',
            'i',
            'f',
            0,
            0, // identifier
            'M',
            'M',
            0,
            0x2A,
            0,
            0,
            0,
            8, // TIFF header, IFD0 at offset 8
            0,
            1, // one entry
            0x01,
            0x12,
            0,
            3,
            0,
            0,
            0,
            1,
            0,
            (byte) orientation,
            0,
            0, // Orientation, SHORT, count 1, value
            0,
            0,
            0,
            0 // no next IFD
        };
    }

    /** "Photoshop 3.0\0" with one 8BIM resource 0x0404 holding IPTC 1:90 (UTF-8) and 2:120 (caption). */
    private static byte[] photoshopIptcCaption(String caption) throws IOException {
        ByteArrayOutputStream iptc = new ByteArrayOutputStream();
        iptc.write(new byte[] {0x1C, 1, 90, 0, 3, 0x1B, 0x25, 0x47}); // coded character set ESC % G = UTF-8
        iptc.write(new byte[] {0x1C, 2, 0, 0, 2, 0, 4}); // record version 4
        byte[] text = caption.getBytes(StandardCharsets.UTF_8);
        iptc.write(new byte[] {0x1C, 2, 120, (byte) (text.length >> 8), (byte) text.length});
        iptc.write(text);
        if (iptc.size() % 2 == 1) {
            iptc.write(0);
        }
        byte[] data = iptc.toByteArray();
        ByteArrayOutputStream irb = new ByteArrayOutputStream();
        irb.write("Photoshop 3.0".getBytes(StandardCharsets.US_ASCII));
        irb.write(0);
        irb.write("8BIM".getBytes(StandardCharsets.US_ASCII));
        irb.write(new byte[] {0x04, 0x04}); // IPTC-NAA resource
        irb.write(new byte[] {0, 0}); // empty Pascal name, padded to even
        irb.write(new byte[] {
            (byte) (data.length >>> 24), (byte) (data.length >>> 16), (byte) (data.length >>> 8), (byte) data.length
        });
        irb.write(data);
        return irb.toByteArray();
    }

    /** 32x24 CMYK JPEG (a four-band raster; the JDK writer adds an Adobe APP14 marker and no JFIF). */
    static byte[] cmykJpeg() throws IOException {
        WritableRaster raster = Raster.createInterleavedRaster(java.awt.image.DataBuffer.TYPE_BYTE, 32, 24, 4, null);
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 32; x++) {
                raster.setPixel(x, y, new int[] {x * 8, y * 10, 40, 20});
            }
        }
        // The JDK's own writer, not a plugin's: the bytes must not depend on what else is on the classpath.
        ImageWriter writer = null;
        for (var writers = ImageIO.getImageWritersByFormatName("jpeg"); writers.hasNext(); ) {
            ImageWriter candidate = writers.next();
            if (candidate.getClass().getName().startsWith("com.sun.")) {
                writer = candidate;
            }
        }
        if (writer == null) {
            throw new IOException("no JDK JPEG writer");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(raster, null, null), writer.getDefaultWriteParam());
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** 60x40 PNG with alpha. */
    static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(60, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0, 128, 0, 200));
        g.fillOval(5, 5, 50, 30);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** 50x30 indexed-colour GIF. */
    static byte[] gif() throws IOException {
        BufferedImage image = new BufferedImage(50, 30, BufferedImage.TYPE_BYTE_INDEXED);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 50, 30);
        g.setColor(Color.RED);
        g.fillRect(10, 5, 30, 20);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "gif", out);
        return out.toByteArray();
    }

    /**
     * 24x16 lossless WebP (VP8L), hand-encoded: left half red, right half blue. No transforms, no colour cache, one
     * prefix-code group of "simple" codes, so each pixel costs two bits (one for red, one for blue).
     */
    static byte[] losslessWebp() {
        int width = 24;
        int height = 16;
        BitWriter bits = new BitWriter();
        bits.write(0x2F, 8); // VP8L signature
        bits.write(width - 1, 14);
        bits.write(height - 1, 14);
        bits.write(0, 1); // alpha_is_used
        bits.write(0, 3); // version
        bits.write(0, 1); // no transform
        bits.write(0, 1); // no colour cache
        bits.write(0, 1); // no meta prefix codes
        simpleCode(bits, 0); // green (and length prefixes): always 0
        // Canonical codes: with two one-bit symbols the smaller symbol gets code 0, the larger code 1.
        simpleCode(bits, 0, 255); // red
        simpleCode(bits, 0, 255); // blue
        simpleCode(bits, 255); // alpha: always 255
        simpleCode(bits, 0); // distance: unused
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean red = x < width / 2;
                bits.write(red ? 1 : 0, 1); // red: 255 or 0
                bits.write(red ? 0 : 1, 1); // blue: 0 or 255
            }
        }
        byte[] vp8l = bits.toByteArray();
        int chunkPadded = vp8l.length + (vp8l.length % 2);
        int riffSize = 4 + 8 + chunkPadded;
        byte[] file = new byte[8 + riffSize];
        put(file, 0, "RIFF");
        putLe32(file, 4, riffSize);
        put(file, 8, "WEBP");
        put(file, 12, "VP8L");
        putLe32(file, 16, vp8l.length);
        System.arraycopy(vp8l, 0, file, 20, vp8l.length);
        return file;
    }

    private static void simpleCode(BitWriter bits, int... symbols) {
        bits.write(1, 1); // simple code
        bits.write(symbols.length - 1, 1);
        bits.write(1, 1); // first symbol uses 8 bits
        bits.write(symbols[0], 8);
        if (symbols.length == 2) {
            bits.write(symbols[1], 8);
        }
    }

    private static void put(byte[] target, int at, String ascii) {
        byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, at, bytes.length);
    }

    private static void putLe32(byte[] target, int at, int value) {
        target[at] = (byte) value;
        target[at + 1] = (byte) (value >>> 8);
        target[at + 2] = (byte) (value >>> 16);
        target[at + 3] = (byte) (value >>> 24);
    }

    /** Least-significant-bit-first bit packing, as VP8L reads it. */
    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int used;

        void write(int value, int count) {
            for (int i = 0; i < count; i++) {
                current |= ((value >>> i) & 1) << used;
                if (++used == 8) {
                    out.write(current);
                    current = 0;
                    used = 0;
                }
            }
        }

        byte[] toByteArray() {
            if (used > 0) {
                out.write(current);
                current = 0;
                used = 0;
            }
            return out.toByteArray();
        }
    }
}

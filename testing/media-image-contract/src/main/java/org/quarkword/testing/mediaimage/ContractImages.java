package org.quarkword.testing.mediaimage;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * The images and file readers of {@link ImageProcessorContract}. They use only the JDK, so a processor's tests need
 * nothing of another processor's libraries: inputs are written and outputs are inspected with the JDK's ImageIO and
 * by reading the bytes.
 */
public final class ContractImages {

    public static final int RED = 0xFF0000;
    public static final int GREEN = 0x00FF00;
    public static final int BLUE = 0x0000FF;
    public static final int WHITE = 0xFFFFFF;

    private ContractImages() {}

    /** A 3x2 PNG whose corners are red (top left), green (top right), blue (bottom left) and white. */
    public static byte[] corners() {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, RED);
        image.setRGB(2, 0, GREEN);
        image.setRGB(0, 1, BLUE);
        image.setRGB(2, 1, WHITE);
        return encode(image, "png");
    }

    /** An 8x6 PNG whose left half is transparent and right half opaque red. */
    public static byte[] alphaPng() {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 6; y++) {
            for (int x = 4; x < 8; x++) {
                image.setRGB(x, y, Color.RED.getRGB());
            }
        }
        return encode(image, "png");
    }

    /** A 2x2 PNG whose every sample is 64, with an {@code iCCP} chunk holding {@code profile}. */
    public static byte[] linearPng(ICC_Profile profile) {
        ComponentColorModel model = new ComponentColorModel(
                new ICC_ColorSpace(profile), false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
        WritableRaster raster = model.createCompatibleWritableRaster(2, 2);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                for (int band = 0; band < 3; band++) {
                    raster.setSample(x, y, band, 64);
                }
            }
        }
        BufferedImage image = new BufferedImage(model, raster, false, null);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), null);
            IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
            IIOMetadataNode iccp = new IIOMetadataNode("iCCP");
            iccp.setAttribute("profileName", "linear");
            iccp.setAttribute("compressionMethod", "deflate");
            iccp.setUserObject(deflate(profile.getData())); // the JDK writes the bytes as they are: zlib data
            root.appendChild(iccp);
            metadata.mergeTree("javax_imageio_png_1.0", root);
            writer.write(null, new IIOImage(image, null, metadata), null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** A 4x4 grey JPEG of samples 100 tagged with the JDK's grey profile, as the JDK's encoder writes it. */
    public static byte[] greyJpeg() {
        ComponentColorModel model = new ComponentColorModel(
                new ICC_ColorSpace(ICC_Profile.getInstance(ColorSpace.CS_GRAY)),
                false,
                false,
                Transparency.OPAQUE,
                DataBuffer.TYPE_BYTE);
        WritableRaster raster = model.createCompatibleWritableRaster(4, 4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                raster.setSample(x, y, 0, 100);
            }
        }
        byte[] bytes = encode(new BufferedImage(model, raster, false, null), "jpeg");
        assertThat(jpegProfileOf(bytes)).isNotNull();
        return bytes;
    }

    /**
     * A {@code w}&times;{@code h} 8-bit RGB PNG of one grey, written row by row so that large sizes cost little: the
     * sources of generated cuts, where only the sizes matter.
     */
    public static byte[] plainPng(int w, int h) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        out.writeBytes(pngChunk(
                "IHDR",
                ByteBuffer.allocate(13)
                        .putInt(w)
                        .putInt(h)
                        .put((byte) 8) // bit depth
                        .put((byte) 2) // RGB
                        .put(new byte[3]) // deflate, adaptive filtering, no interlace
                        .array()));
        byte[] row = new byte[1 + 3 * w]; // filter type 0, then the samples
        Arrays.fill(row, 1, row.length, (byte) 0x80);
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (java.util.zip.DeflaterOutputStream zip = new java.util.zip.DeflaterOutputStream(idat, deflater)) {
            for (int y = 0; y < h; y++) {
                zip.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deflater.end();
        }
        out.writeBytes(pngChunk("IDAT", idat.toByteArray()));
        out.writeBytes(pngChunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    /** One PNG chunk: length, type, data, CRC. */
    private static byte[] pngChunk(String type, byte[] data) {
        byte[] name = ascii(type);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(name);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length)
                .putInt(data.length)
                .put(name)
                .put(data)
                .putInt((int) crc.getValue())
                .array();
    }

    /** A small four-component (CMYK) JPEG without a colour profile or Adobe marker. */
    public static byte[] cmykJpeg() {
        try (InputStream in = ContractImages.class.getResourceAsStream("cmyk.jpg")) {
            if (in == null) {
                throw new IllegalStateException("cmyk.jpg is missing from the contract's resources");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * An RGB display profile other than sRGB: Adobe RGB (1998) primaries adapted to D50 and a gamma of 563/256 per
     * channel, written here (version 2.1) so that no image library's bundled profile is needed. The bytes are as the
     * JDK's colour management writes them back, so a profile kept unchanged compares equal.
     */
    public static byte[] rgbProfile() {
        return ICC_Profile.getInstance(wideRgbProfile()).getData();
    }

    /** The bytes of {@code image} written by the JDK's ImageIO writer for {@code format}. */
    public static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            assertThat(ImageIO.write(image, format, out)).isTrue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** The ICC profile of the JPEG's APP2 {@code ICC_PROFILE} segments as the JDK reads it back, or null. */
    public static byte[] jpegProfile(Path file) throws IOException {
        return jpegProfileOf(Files.readAllBytes(file));
    }

    /** {@link #jpegProfile(Path)} of the JPEG's bytes. */
    public static byte[] jpegProfileOf(byte[] b) {
        // the APP2 segments named ICC_PROFILE before the first scan, joined in their sequence numbers' order
        byte[] name = "ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1);
        byte[][] parts = null;
        int i = 2; // after SOI
        while (i + 3 < b.length && (b[i] & 0xFF) == 0xFF) {
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int length = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            int data = i + 4;
            int end = i + 2 + length;
            if (marker == 0xE2
                    && end <= b.length
                    && length >= 2 + name.length + 2
                    && Arrays.equals(b, data, data + name.length, name, 0, name.length)) {
                int sequence = b[data + name.length] & 0xFF;
                int count = b[data + name.length + 1] & 0xFF;
                if (parts == null) {
                    parts = new byte[count][];
                }
                if (sequence >= 1 && sequence <= parts.length) {
                    parts[sequence - 1] = Arrays.copyOfRange(b, data + name.length + 2, end);
                }
            }
            i = end;
        }
        if (parts == null) {
            return null;
        }
        ByteArrayOutputStream profile = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            if (part == null) {
                throw new AssertionError("an ICC_PROFILE segment is missing");
            }
            profile.writeBytes(part);
        }
        return ICC_Profile.getInstance(profile.toByteArray()).getData();
    }

    /** The bytes of the profile in the PNG's {@code iCCP} chunk, or null. */
    public static byte[] pngProfile(Path file) throws IOException {
        // read from the chunk itself, as any PNG reader must: name, NUL, compression method 0, then zlib data
        byte[] b = Files.readAllBytes(file);
        for (int i = 8; i + 8 <= b.length; ) {
            int length = ByteBuffer.wrap(b, i, 4).getInt();
            String type = new String(b, i + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("iCCP")) {
                int at = i + 8;
                while (b[at] != 0) {
                    at++;
                }
                assertThat(b[at + 1]).as("compression method").isZero();
                return inflate(Arrays.copyOfRange(b, at + 2, i + 8 + length));
            }
            i += 12 + length;
        }
        return null;
    }

    /** Whether the JPEG holds a segment with {@code marker} before its first scan. */
    public static boolean hasMarker(Path file, int marker) throws IOException {
        byte[] b = Files.readAllBytes(file);
        int i = 2; // after SOI
        while (i + 3 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                throw new IllegalStateException("not a marker at " + i);
            }
            int m = b[i + 1] & 0xFF;
            if (m == marker) {
                return true;
            }
            if (m == 0xDA) {
                return false;
            }
            i += 2 + (((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF));
        }
        return false;
    }

    // ---- the RGB profile -------------------------------------------------------------------------------------------

    private static byte[] wideRgbProfile() {
        byte[] desc = textDescription("QuarkWord contract RGB (Adobe RGB (1998) primaries)");
        byte[] cprt = text("No copyright, use freely");
        byte[] wtpt = xyz(0.9642, 1.0, 0.8249);
        byte[] red = xyz(0.6097, 0.3111, 0.0195);
        byte[] green = xyz(0.2053, 0.6257, 0.0609);
        byte[] blue = xyz(0.1492, 0.0632, 0.7446);
        byte[] trc = gamma(0x0233); // 2.19921875 as u8Fixed8
        String[] signatures = {"desc", "cprt", "wtpt", "rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC"};
        byte[][] data = {desc, cprt, wtpt, red, green, blue, trc, trc, trc};
        int tableEnd = 128 + 4 + 12 * signatures.length;
        int[] offsets = new int[signatures.length];
        int at = tableEnd;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int t = 0; t < signatures.length; t++) {
            if (t > 0 && data[t] == data[t - 1]) {
                offsets[t] = offsets[t - 1]; // the three curves share one tag
                continue;
            }
            offsets[t] = at;
            body.writeBytes(data[t]);
            at += data[t].length;
            while (at % 4 != 0) {
                body.write(0);
                at++;
            }
        }
        ByteBuffer profile = ByteBuffer.allocate(at);
        profile.putInt(at); // size
        profile.putInt(0); // preferred CMM
        profile.putInt(0x02100000); // version 2.1
        profile.put(ascii("mntr")).put(ascii("RGB ")).put(ascii("XYZ "));
        profile.putShort((short) 2026).putShort((short) 1).putShort((short) 1);
        profile.putShort((short) 0).putShort((short) 0).putShort((short) 0);
        profile.put(ascii("acsp"));
        profile.position(64);
        profile.putInt(0); // perceptual
        profile.putInt(fixed(0.9642)).putInt(fixed(1.0)).putInt(fixed(0.8249)); // D50
        profile.position(128);
        profile.putInt(signatures.length);
        for (int t = 0; t < signatures.length; t++) {
            profile.put(ascii(signatures[t])).putInt(offsets[t]).putInt(data[t].length);
        }
        profile.put(body.toByteArray());
        return profile.array();
    }

    private static byte[] textDescription(String text) {
        byte[] chars = ascii(text);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4 + chars.length + 1 + 4 + 4 + 2 + 1 + 67);
        b.put(ascii("desc")).putInt(0).putInt(chars.length + 1).put(chars).put((byte) 0);
        return b.array(); // no Unicode and no ScriptCode description: the rest stays zero
    }

    private static byte[] text(String text) {
        byte[] chars = ascii(text);
        return ByteBuffer.allocate(8 + chars.length + 1)
                .put(ascii("text"))
                .putInt(0)
                .put(chars)
                .array();
    }

    private static byte[] xyz(double x, double y, double z) {
        return ByteBuffer.allocate(20)
                .put(ascii("XYZ "))
                .putInt(0)
                .putInt(fixed(x))
                .putInt(fixed(y))
                .putInt(fixed(z))
                .array();
    }

    private static byte[] gamma(int u8Fixed8) {
        return ByteBuffer.allocate(14)
                .put(ascii("curv"))
                .putInt(0)
                .putInt(1)
                .putShort((short) u8Fixed8)
                .array();
    }

    private static int fixed(double value) {
        return (int) Math.round(value * 65536);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    // ---- zlib ------------------------------------------------------------------------------------------------------

    private static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && inflater.needsInput()) {
                    throw new AssertionError("the iCCP data ends early");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new AssertionError("the iCCP data is not zlib data", e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(9);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}

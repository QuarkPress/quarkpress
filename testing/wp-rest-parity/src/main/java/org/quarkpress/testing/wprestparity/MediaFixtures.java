package org.quarkpress.testing.wprestparity;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;

/**
 * The media files the WordPress facts run, the media parity tier and media's own tests upload: images drawn with the
 * JDK, and the EXIF (APP1), XMP (APP1), ICC (APP2) and IPTC (APP13) segments of a JPEG, the PNG {@code iTXt} XMP chunk
 * and every non-image container written by hand, so the same bytes exist on every machine without external tools. The
 * files are small and deterministic: the same arguments give the same bytes. They are fixtures, not samples of real
 * cameras or encoders: each holds just enough structure for a type detector and a header reader.
 */
public final class MediaFixtures {

    private MediaFixtures() {}

    /**
     * What {@link #jpeg(int, int, JpegMeta)} writes into the file besides the pixels.
     *
     * @param orientation the EXIF Orientation (IFD0, SHORT), written as given, also outside 1-8
     * @param iptc IPTC record 2 datasets by number ({@code 105} = 2:105 Headline) to their repetitions, in order;
     *     written as UTF-8 with the 1:90 UTF-8 marker, unless the map holds key {@code 0}, which is not written and
     *     whose first value names the charset of every value instead (for example {@code "ISO-8859-1"}, without the
     *     1:90 marker)
     * @param exif EXIF fields by name: {@code ImageDescription}, {@code Model}, {@code Artist}, {@code Copyright}
     *     (IFD0, ASCII), {@code Comments} (IFD0 0x9C9C, UCS-2 in the file's big-endian order), {@code UserComment}
     *     (ASCII character code), {@code FNumber}, {@code ExposureTime}, {@code FocalLength} (RATIONAL, as
     *     {@code "28/10"}), {@code ISOSpeedRatings} (SHORT, several values comma-separated),
     *     {@code DateTimeDigitized}, {@code DateTimeOriginal} (ASCII, {@code "2023:10:15 14:30:00"}); any other name is
     *     refused
     * @param xmp a whole {@code x:xmpmeta} packet, written as the standard XMP APP1 segment
     * @param icc an ICC profile's bytes, written as one {@code ICC_PROFILE} APP2 segment
     *     <p>The record is immutable: the maps and lists are unmodifiable copies in the given order, and the profile is
     *     copied in and out.
     */
    public record JpegMeta(
            Optional<Integer> orientation,
            Map<Integer, List<String>> iptc,
            Map<String, String> exif,
            Optional<String> xmp,
            Optional<byte[]> icc) {

        public JpegMeta {
            Objects.requireNonNull(orientation, "orientation");
            Objects.requireNonNull(xmp, "xmp");
            Objects.requireNonNull(icc, "icc");
            Map<Integer, List<String>> datasets = new LinkedHashMap<>();
            iptc.forEach((dataset, values) -> datasets.put(dataset, List.copyOf(values)));
            iptc = Collections.unmodifiableMap(datasets);
            exif = Collections.unmodifiableMap(new LinkedHashMap<>(exif));
            for (String name : exif.keySet()) {
                if (!EXIF_TAGS.containsKey(name)) {
                    throw new IllegalArgumentException("unsupported EXIF field " + name);
                }
            }
            icc = icc.map(byte[]::clone);
        }

        /** A copy of the profile's bytes. */
        @Override
        public Optional<byte[]> icc() {
            return icc.map(byte[]::clone);
        }

        /** Equal when every field is, the profile compared by its bytes. */
        @Override
        public boolean equals(Object other) {
            return other instanceof JpegMeta that
                    && orientation.equals(that.orientation)
                    && iptc.equals(that.iptc)
                    && exif.equals(that.exif)
                    && xmp.equals(that.xmp)
                    && icc.isPresent() == that.icc.isPresent()
                    && (icc.isEmpty() || Arrays.equals(icc.get(), that.icc.get()));
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    orientation, iptc, exif, xmp, icc.map(Arrays::hashCode).orElse(0));
        }

        @Override
        public String toString() {
            return "JpegMeta[orientation=" + orientation + ", iptc=" + iptc + ", exif=" + exif + ", xmp=" + xmp
                    + ", icc=" + icc.map(bytes -> bytes.length + " bytes").orElse("none") + "]";
        }

        /** Nothing but the pixels. */
        public static JpegMeta none() {
            return new JpegMeta(Optional.empty(), Map.of(), Map.of(), Optional.empty(), Optional.empty());
        }

        public JpegMeta withOrientation(int value) {
            return new JpegMeta(Optional.of(value), iptc, exif, xmp, icc);
        }

        public JpegMeta withIptc(int dataset, String... values) {
            Map<Integer, List<String>> copy = new LinkedHashMap<>(iptc);
            copy.put(dataset, List.of(values));
            return new JpegMeta(orientation, copy, exif, xmp, icc);
        }

        public JpegMeta withExif(String name, String value) {
            Map<String, String> copy = new LinkedHashMap<>(exif);
            copy.put(name, value);
            return new JpegMeta(orientation, iptc, copy, xmp, icc);
        }

        public JpegMeta withXmp(String packet) {
            return new JpegMeta(orientation, iptc, exif, Optional.of(packet), icc);
        }

        public JpegMeta withIcc(byte[] profile) {
            return new JpegMeta(orientation, iptc, exif, xmp, Optional.of(profile));
        }
    }

    /** Tag number and IFD (0 = IFD0, 1 = the EXIF sub-IFD) of each supported EXIF field. */
    private static final Map<String, int[]> EXIF_TAGS = Map.ofEntries(
            Map.entry("ImageDescription", new int[] {0x010E, 0}),
            Map.entry("Model", new int[] {0x0110, 0}),
            Map.entry("Artist", new int[] {0x013B, 0}),
            Map.entry("Copyright", new int[] {0x8298, 0}),
            Map.entry("Comments", new int[] {0x9C9C, 0}),
            Map.entry("ExposureTime", new int[] {0x829A, 1}),
            Map.entry("FNumber", new int[] {0x829D, 1}),
            Map.entry("ISOSpeedRatings", new int[] {0x8827, 1}),
            Map.entry("DateTimeOriginal", new int[] {0x9003, 1}),
            Map.entry("DateTimeDigitized", new int[] {0x9004, 1}),
            Map.entry("FocalLength", new int[] {0x920A, 1}),
            Map.entry("UserComment", new int[] {0x9286, 1}));

    // ---- raster images -------------------------------------------------------------------------------------------

    /**
     * A {@code w}&times;{@code h} baseline RGB JPEG (JFIF) of a red-to-blue gradient, as the JDK's encoder writes it.
     */
    public static byte[] jpeg(int w, int h) {
        return encode(gradient(w, h, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    /** {@link #jpeg(int, int)} with the segments of {@code meta} inserted right after the JFIF APP0 segment. */
    public static byte[] jpeg(int w, int h, JpegMeta meta) {
        Objects.requireNonNull(meta, "meta");
        byte[] plain = jpeg(w, h);
        int app0Length = ((plain[4] & 0xff) << 8) | (plain[5] & 0xff);
        int insertAt = 4 + app0Length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length + 4096);
        out.write(plain, 0, insertAt);
        if (meta.orientation().isPresent() || !meta.exif().isEmpty()) {
            out.writeBytes(segment(0xE1, exif(meta.orientation(), meta.exif())));
        }
        meta.xmp().ifPresent(packet -> out.writeBytes(segment(0xE1, xmpApp1(packet))));
        meta.icc().ifPresent(profile -> out.writeBytes(segment(0xE2, iccApp2(profile))));
        if (!meta.iptc().isEmpty()) {
            out.writeBytes(segment(0xED, photoshopIptc(meta.iptc())));
        }
        out.write(plain, insertAt, plain.length - insertAt);
        return out.toByteArray();
    }

    /** A {@code w}&times;{@code h} RGB PNG of the gradient. */
    public static byte[] png(int w, int h) {
        return encode(gradient(w, h, BufferedImage.TYPE_INT_RGB), "png");
    }

    /** {@link #png(int, int)} with an {@code iTXt} chunk {@code XML:com.adobe.xmp} holding the packet, after IHDR. */
    public static byte[] png(int w, int h, Optional<String> xmp) {
        byte[] plain = png(w, h);
        if (xmp.isEmpty()) {
            return plain;
        }
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        text.writeBytes("XML:com.adobe.xmp".getBytes(StandardCharsets.ISO_8859_1));
        text.write(0); // keyword end
        text.write(0); // not compressed
        text.write(0); // compression method
        text.write(0); // empty language tag
        text.write(0); // empty translated keyword
        text.writeBytes(xmp.get().getBytes(StandardCharsets.UTF_8));
        int afterIhdr = 8 + 4 + 4 + 13 + 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length + text.size() + 12);
        out.write(plain, 0, afterIhdr);
        out.writeBytes(pngChunk("iTXt", text.toByteArray()));
        out.write(plain, afterIhdr, plain.length - afterIhdr);
        return out.toByteArray();
    }

    /**
     * A {@code w}&times;{@code h} GIF; with {@code frames} > 1 an animation of that many frames (no loop extension).
     */
    public static byte[] gif(int w, int h, int frames) {
        if (frames < 1) {
            throw new IllegalArgumentException("frames < 1");
        }
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(stream);
                if (frames == 1) {
                    writer.write(indexed(w, h, 0));
                } else {
                    writer.prepareWriteSequence(null);
                    for (int frame = 0; frame < frames; frame++) {
                        BufferedImage image = indexed(w, h, frame);
                        IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), null);
                        writer.writeToSequence(new IIOImage(image, null, metadata), null);
                    }
                    writer.endWriteSequence();
                }
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A {@code w}&times;{@code h} 24-bit BMP of the gradient. */
    public static byte[] bmp(int w, int h) {
        return encode(gradient(w, h, BufferedImage.TYPE_INT_RGB), "bmp");
    }

    /** A {@code w}&times;{@code h} uncompressed RGB TIFF of the gradient (the JDK's TIFF writer, big-endian). */
    public static byte[] tiff(int w, int h) {
        return encode(gradient(w, h, BufferedImage.TYPE_INT_RGB), "tiff");
    }

    /** A {@code w}&times;{@code h} progressive RGB JPEG of the gradient. */
    public static byte[] progressiveJpeg(int w, int h) {
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(gradient(w, h, BufferedImage.TYPE_INT_RGB), null, null), param);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A {@code w}&times;{@code h} PNG whose left half is red and right half blue, for telling crops apart: the colour
     * of a crop's pixels shows which part of the original it holds.
     */
    public static byte[] halvesPng(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        return encode(image, "png");
    }

    /**
     * The 24&times;16 lossless WebP (VP8L) of the native-image spike, hand-encoded: left half red, right half blue. No
     * transforms, no colour cache, one prefix-code group of simple codes.
     */
    public static byte[] webp() {
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
        simpleCode(bits, 0); // green
        simpleCode(bits, 0, 255); // red
        simpleCode(bits, 0, 255); // blue
        simpleCode(bits, 255); // alpha
        simpleCode(bits, 0); // distance
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean red = x < width / 2;
                bits.write(red ? 1 : 0, 1);
                bits.write(red ? 0 : 1, 1);
            }
        }
        byte[] vp8l = bits.toByteArray();
        int chunkPadded = vp8l.length + (vp8l.length % 2);
        int riffSize = 4 + 8 + chunkPadded;
        ByteBuffer file = ByteBuffer.allocate(8 + riffSize).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        file.put(ascii("RIFF")).putInt(riffSize).put(ascii("WEBP")).put(ascii("VP8L"));
        file.putInt(vp8l.length).put(vp8l);
        return file.array();
    }

    /**
     * An ISO base media file with an {@code ftyp} box of major brand {@code brand} (compatible: the brand and
     * {@code mif1}), a {@code meta} box whose item properties hold an {@code ispe} of 3000&times;2000, a
     * {@code pixi} (three 8-bit channels) and an empty codec configuration ({@code av1C} or {@code hvcC}), and an empty
     * {@code mdat}: the header of a HEIF-family (or, for {@code avif}/{@code avis}, AVIF) image without coded pixels.
     */
    public static byte[] heif(String brand) {
        if (brand.length() != 4) {
            throw new IllegalArgumentException("a brand has four characters: " + brand);
        }
        byte[] ftyp = box("ftyp", ascii(brand), u32(0), ascii(brand), ascii("mif1"));
        byte[] hdlr = fullBox("hdlr", 0, 0, u32(0), ascii("pict"), u32(0), u32(0), u32(0), new byte[] {0});
        byte[] pitm = fullBox("pitm", 0, 0, u16(1));
        byte[] infe =
                fullBox("infe", 2, 0, u16(1), u16(0), ascii(brand.startsWith("av") ? "av01" : "hvc1"), new byte[] {0});
        byte[] iinf = fullBox("iinf", 0, 0, u16(1), infe);
        byte[] ispe = fullBox("ispe", 0, 0, u32(3000), u32(2000));
        byte[] pixi = fullBox("pixi", 0, 0, new byte[] {3, 8, 8, 8});
        byte[] codec = brand.startsWith("av")
                ? box("av1C", new byte[] {(byte) 0x81, 0x00, 0x0C, 0x00})
                : box("hvcC", new byte[23]);
        byte[] ipco = box("ipco", ispe, pixi, codec);
        byte[] ipma = fullBox("ipma", 0, 0, u32(1), u16(1), new byte[] {3, (byte) 0x81, (byte) 0x02, (byte) 0x83});
        byte[] iprp = box("iprp", ipco, ipma);
        byte[] meta = fullBox("meta", 0, 0, hdlr, pitm, iinf, iprp);
        return concat(ftyp, meta, box("mdat"));
    }

    /** A 16&times;16 32-bit icon (ICO with one BMP-coded image). */
    public static byte[] ico() {
        int size = 16;
        int pixels = size * size * 4;
        int mask = size * 4; // 16 rows of 32 bits (16 mask bits padded to 4 bytes)
        int imageSize = 40 + pixels + mask;
        ByteBuffer out = ByteBuffer.allocate(6 + 16 + imageSize).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) 0).putShort((short) 1).putShort((short) 1); // reserved, type icon, one image
        out.put((byte) size).put((byte) size).put((byte) 0).put((byte) 0); // width, height, colours, reserved
        out.putShort((short) 1).putShort((short) 32).putInt(imageSize).putInt(22); // planes, bpp, size, offset
        out.putInt(40).putInt(size).putInt(size * 2).putShort((short) 1).putShort((short) 32); // BITMAPINFOHEADER
        out.putInt(0).putInt(pixels + mask).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int i = 0; i < size * size; i++) {
            out.put((byte) 0xFF).put((byte) 0).put((byte) 0).put((byte) 0xFF); // BGRA: opaque blue
        }
        out.put(new byte[mask]);
        return out.array();
    }

    /** A 4&times;4 RGB Photoshop file (8BPS, version 1, raw image data). */
    public static byte[] psd() {
        int w = 4;
        int h = 4;
        ByteBuffer out = ByteBuffer.allocate(26 + 4 + 4 + 4 + 2 + 3 * w * h);
        out.put(ascii("8BPS"))
                .putShort((short) 1)
                .put(new byte[6])
                .putShort((short) 3)
                .putInt(h)
                .putInt(w);
        out.putShort((short) 8).putShort((short) 3); // depth 8, mode RGB
        out.putInt(0).putInt(0).putInt(0); // colour mode data, image resources, layer and mask information
        out.putShort((short) 0); // raw image data
        for (int i = 0; i < 3 * w * h; i++) {
            out.put((byte) (i * 16));
        }
        return out.array();
    }

    /** A one-page PDF 1.4 with a correct cross-reference table. */
    public static byte[] pdf() {
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents 4 0 R /Resources << >> >>",
                "<< /Length 0 >>\nstream\n\nendstream");
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size ")
                .append(objects.size() + 1)
                .append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref)
                .append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * An Office Open XML package whose main part has {@code contentType}: {@code [Content_Types].xml} first, then
     * {@code _rels/.rels}, then the main part ({@code word/document.xml}, {@code xl/workbook.xml} or
     * {@code ppt/presentation.xml}, chosen by the type).
     */
    public static byte[] zipOoxml(String contentType) {
        String part = contentType.contains("spreadsheetml")
                ? "xl/workbook.xml"
                : contentType.contains("presentationml") ? "ppt/presentation.xml" : "word/document.xml";
        String types = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\""
                + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/" + part + "\" ContentType=\"" + contentType + ".main+xml\"/></Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/officeDocument\" Target=\"" + part + "\"/></Relationships>";
        String main = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<root/>";
        return zip(List.of(
                new ZipPart("[Content_Types].xml", types.getBytes(StandardCharsets.UTF_8), false),
                new ZipPart("_rels/.rels", rels.getBytes(StandardCharsets.UTF_8), false),
                new ZipPart(part, main.getBytes(StandardCharsets.UTF_8), false)));
    }

    /** An OpenDocument text: a stored {@code mimetype} entry first, then the manifest and the content. */
    public static byte[] odt() {
        String manifest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\">"
                + "<manifest:file-entry manifest:full-path=\"/\" "
                + "manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>"
                + "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>"
                + "</manifest:manifest>";
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
                + "office:version=\"1.2\"><office:body><office:text/></office:body></office:document-content>";
        return zip(List.of(
                new ZipPart(
                        "mimetype",
                        "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.US_ASCII),
                        true),
                new ZipPart("META-INF/manifest.xml", manifest.getBytes(StandardCharsets.UTF_8), false),
                new ZipPart("content.xml", content.getBytes(StandardCharsets.UTF_8), false)));
    }

    /**
     * A legacy Excel workbook container: a compound file (version 3, 512-byte sectors) whose root holds one 4096-byte
     * stream {@code Workbook} starting with a BIFF8 workbook BOF record.
     */
    public static byte[] xls() {
        int sector = 512;
        ByteBuffer out = ByteBuffer.allocate(sector * 11).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
        out.put(new byte[16]).putShort((short) 0x3E).putShort((short) 3).putShort((short) 0xFFFE);
        out.putShort((short) 9).putShort((short) 6).put(new byte[6]);
        out.putInt(0).putInt(1).putInt(1).putInt(0).putInt(4096); // dir sectors, FAT sectors, first dir, txn, cutoff
        out.putInt(0xFFFFFFFE).putInt(0).putInt(0xFFFFFFFE).putInt(0); // mini FAT, DIFAT
        out.putInt(0); // DIFAT[0]: the FAT is sector 0
        for (int i = 1; i < 109; i++) {
            out.putInt(0xFFFFFFFF);
        }
        // sector 0: the FAT. 0 = FAT sector, 1 = directory, 2..9 = the Workbook stream.
        out.putInt(0xFFFFFFFD).putInt(0xFFFFFFFE);
        for (int s = 2; s < 9; s++) {
            out.putInt(s + 1);
        }
        out.putInt(0xFFFFFFFE);
        for (int i = 10; i < sector / 4; i++) {
            out.putInt(0xFFFFFFFF);
        }
        // sector 1: the directory.
        directoryEntry(out, "Root Entry", 5, 1, 0xFFFFFFFE, 0);
        directoryEntry(out, "Workbook", 2, 0xFFFFFFFF, 2, 4096);
        for (int i = 0; i < 2; i++) {
            out.put(new byte[64]).putShort((short) 0).put((byte) 0).put((byte) 0);
            out.putInt(0xFFFFFFFF)
                    .putInt(0xFFFFFFFF)
                    .putInt(0xFFFFFFFF)
                    .put(new byte[36])
                    .putInt(0)
                    .putLong(0);
        }
        // sectors 2..9: the Workbook stream, a BOF record (BIFF8, workbook globals), then zeros.
        out.putShort((short) 0x0809)
                .putShort((short) 16)
                .putShort((short) 0x0600)
                .putShort((short) 0x0005);
        out.putShort((short) 0x0DBB).putShort((short) 0x07CC).putInt(0).putInt(0x06);
        return out.array();
    }

    private static void directoryEntry(ByteBuffer out, String name, int type, int child, int start, long size) {
        byte[] utf16 = (name + "\0").getBytes(StandardCharsets.UTF_16LE);
        byte[] field = new byte[64];
        System.arraycopy(utf16, 0, field, 0, utf16.length);
        out.put(field).putShort((short) utf16.length).put((byte) type).put((byte) 1);
        out.putInt(0xFFFFFFFF).putInt(0xFFFFFFFF).putInt(child); // left, right, child
        out.put(new byte[16]).putInt(0).put(new byte[16]); // CLSID, state bits, times
        out.putInt(start).putLong(size);
    }

    /** {@link #png(int, int)} with an {@code eXIf} chunk (after IHDR) holding an EXIF Orientation. */
    public static byte[] pngExifOrientation(int w, int h, int orientation) {
        byte[] plain = png(w, h);
        byte[] app1 = exif(Optional.of(orientation), Map.of());
        byte[] tiff = java.util.Arrays.copyOfRange(app1, 6, app1.length); // without "Exif\0\0"
        int afterIhdr = 8 + 4 + 4 + 13 + 4;
        return concat(
                java.util.Arrays.copyOfRange(plain, 0, afterIhdr),
                pngChunk("eXIf", tiff),
                java.util.Arrays.copyOfRange(plain, afterIhdr, plain.length));
    }

    // ---- audio and video ---------------------------------------------------------------------------------------

    /** The ID3v2.3 text frame of each {@link #mp3} tag name. */
    private static final Map<String, String> ID3_FRAMES = Map.of(
            "title", "TIT2",
            "artist", "TPE1",
            "album", "TALB",
            "year", "TYER",
            "track_number", "TRCK",
            "genre", "TCON",
            "composer", "TCOM",
            "band", "TPE2");

    /**
     * One second of MPEG-1 Layer III frames (128 kbit/s, 44.1 kHz, stereo, silent payload), preceded by an ID3v2.3 tag
     * when {@code id3} or {@code cover} is given: a text frame (ISO-8859-1) per entry of {@code id3} ({@code title},
     * {@code artist}, {@code album}, {@code year}, {@code track_number}, {@code genre}, {@code composer},
     * {@code band}) and an {@code APIC} front cover holding {@code cover} as {@code image/jpeg}.
     */
    public static byte[] mp3(Map<String, String> id3, Optional<byte[]> cover) {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (Map.Entry<String, String> tag : id3.entrySet()) {
            String id = ID3_FRAMES.get(tag.getKey());
            if (id == null) {
                throw new IllegalArgumentException("unsupported ID3 tag " + tag.getKey());
            }
            byte[] text = tag.getValue().getBytes(StandardCharsets.ISO_8859_1);
            frames.writeBytes(id3Frame(id, concat(new byte[] {0}, text)));
        }
        cover.ifPresent(picture -> frames.writeBytes(id3Frame(
                "APIC",
                concat(
                        new byte[] {0},
                        ascii("image/jpeg"),
                        new byte[] {0, 3},
                        ascii("Cover"),
                        new byte[] {0},
                        picture))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (frames.size() > 0) {
            out.writeBytes(ascii("ID3"));
            out.write(3);
            out.write(0);
            out.write(0);
            out.writeBytes(syncsafe(frames.size()));
            out.writeBytes(frames.toByteArray());
        }
        int frameLength = 144 * 128000 / 44100; // 417 bytes, no padding
        for (int i = 0; i < 39; i++) {
            byte[] frame = new byte[frameLength];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xFB; // MPEG-1, Layer III, no CRC
            frame[2] = (byte) 0x90; // 128 kbit/s, 44.1 kHz, no padding
            frame[3] = (byte) 0x00; // stereo
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    /**
     * An MP4 ({@code ftyp isom}) of {@code seconds} seconds with one {@code avc1} video track of
     * {@code w}&times;{@code h}: {@code mvhd} and {@code tkhd} carry the duration and the dimensions; the sample
     * tables describe one empty sample in an empty {@code mdat}.
     */
    public static byte[] mp4(int w, int h, int seconds) {
        byte[] stsd = fullBox(
                "stsd",
                0,
                0,
                u32(1),
                box(
                        "avc1",
                        new byte[6],
                        u16(1),
                        new byte[16],
                        u16(w),
                        u16(h),
                        u32(0x00480000),
                        u32(0x00480000),
                        u32(0),
                        u16(1),
                        new byte[32],
                        u16(0x18),
                        u16(0xFFFF)));
        byte[] minf = box("minf", fullBox("vmhd", 0, 1, new byte[8]), dinf(), stbl(stsd, seconds * 1000));
        return isoMedia("isom", "vide", minf, w, h, seconds, 1000);
    }

    /** An M4A ({@code ftyp M4A }) of one second with one {@code mp4a} audio track (44.1 kHz stereo). */
    public static byte[] m4a() {
        byte[] stsd = fullBox(
                "stsd",
                0,
                0,
                u32(1),
                box("mp4a", new byte[6], u16(1), new byte[8], u16(2), u16(16), u16(0), u16(0), u32(44100 << 16)));
        byte[] minf = box("minf", fullBox("smhd", 0, 0, new byte[4]), dinf(), stbl(stsd, 44100));
        return isoMedia("M4A ", "soun", minf, 0, 0, 1, 44100);
    }

    /** A PCM WAV of {@code seconds} seconds: 8 kHz, mono, 8 bits, silence. */
    public static byte[] wav(int seconds) {
        int data = 8000 * seconds;
        ByteBuffer out = ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.put(ascii("RIFF")).putInt(36 + data).put(ascii("WAVE"));
        out.put(ascii("fmt "))
                .putInt(16)
                .putShort((short) 1)
                .putShort((short) 1)
                .putInt(8000)
                .putInt(8000);
        out.putShort((short) 1).putShort((short) 8);
        out.put(ascii("data")).putInt(data);
        for (int i = 0; i < data; i++) {
            out.put((byte) 0x80);
        }
        return out.array();
    }

    /** One Ogg page holding a Vorbis identification header (44.1 kHz stereo) and nothing else. */
    public static byte[] ogg() {
        ByteBuffer id = ByteBuffer.allocate(30).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        id.put((byte) 1).put(ascii("vorbis")).putInt(0).put((byte) 2).putInt(44100);
        id.putInt(0).putInt(128000).putInt(0).put((byte) 0xB8).put((byte) 1);
        return oggPage(id.array(), 0x02, 0);
    }

    /** One Ogg page holding a Theora identification header (16&times;16), the start of an Ogg video. */
    public static byte[] ogv() {
        ByteBuffer id = ByteBuffer.allocate(42);
        id.put((byte) 0x80).put(ascii("theora")).put((byte) 3).put((byte) 2).put((byte) 1);
        id.putShort((short) 1).putShort((short) 1); // frame size in macroblocks
        id.put(new byte[] {0, 0, 16, 0, 0, 16, 0, 0}); // picture width and height (24 bits each), offsets
        id.putInt(25).putInt(1).put(new byte[] {0, 0, 1, 0, 0, 1}).put((byte) 0); // fps, aspect, colour space
        id.put(new byte[] {0, 0, 0}).putShort((short) (6 << 6)); // bitrate, quality/shift bits
        return oggPage(id.array(), 0x02, 0);
    }

    /**
     * A WebM (Matroska with DocType {@code webm}) whose Info gives a 2-second duration and whose one video track is a
     * 32&times;24 {@code V_VP8} track, with no clusters.
     */
    public static byte[] webm() {
        byte[] header = ebml(
                0x1A45DFA3,
                ebml(0x4286, new byte[] {1}),
                ebml(0x42F7, new byte[] {1}),
                ebml(0x42F2, new byte[] {4}),
                ebml(0x42F3, new byte[] {8}),
                ebml(0x4282, ascii("webm")),
                ebml(0x4287, new byte[] {2}),
                ebml(0x4285, new byte[] {2}));
        byte[] info = ebml(
                0x1549A966,
                ebml(0x2AD7B1, new byte[] {0x0F, 0x42, 0x40}),
                ebml(0x4489, ByteBuffer.allocate(8).putDouble(2000.0).array()),
                ebml(0x4D80, ascii("qp")),
                ebml(0x5741, ascii("qp")));
        byte[] video = ebml(0xE0, ebml(0xB0, new byte[] {32}), ebml(0xBA, new byte[] {24}));
        byte[] track = ebml(
                0xAE,
                ebml(0xD7, new byte[] {1}),
                ebml(0x73C5, new byte[] {1}),
                ebml(0x83, new byte[] {1}),
                ebml(0x86, ascii("V_VP8")),
                video);
        byte[] segment = ebml(0x18538067, info, ebml(0x1654AE6B, track));
        return concat(header, segment);
    }

    /** {@code content} as UTF-8. */
    public static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A PNG whose IHDR declares {@code w}&times;{@code h} (8-bit RGB) and whose one IDAT holds a few compressed zero
     * bytes: the header of a huge image without its pixels, for the pixel-limit checks.
     */
    public static byte[] pngHeaderOnly(int w, int h) {
        byte[] ihdr = ByteBuffer.allocate(13)
                .putInt(w)
                .putInt(h)
                .put((byte) 8)
                .put((byte) 2)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .array();
        Deflater deflater = new Deflater();
        deflater.setInput(new byte[16]);
        deflater.finish();
        byte[] buffer = new byte[64];
        int length = deflater.deflate(buffer);
        deflater.end();
        byte[] idat = java.util.Arrays.copyOf(buffer, length);
        return concat(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'},
                pngChunk("IHDR", ihdr),
                pngChunk("IDAT", idat),
                pngChunk("IEND", new byte[0]));
    }

    // ---- JPEG segments ---------------------------------------------------------------------------------------------

    private static byte[] segment(int marker, byte[] payload) {
        if (payload.length + 2 > 0xFFFF) {
            throw new IllegalArgumentException("a JPEG segment holds at most 65533 bytes");
        }
        int length = payload.length + 2;
        byte[] segment = new byte[payload.length + 4];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) marker;
        segment[2] = (byte) (length >> 8);
        segment[3] = (byte) length;
        System.arraycopy(payload, 0, segment, 4, payload.length);
        return segment;
    }

    private static byte[] xmpApp1(String packet) {
        return concat(ascii("http://ns.adobe.com/xap/1.0/"), new byte[] {0}, packet.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] iccApp2(byte[] profile) {
        return concat(ascii("ICC_PROFILE"), new byte[] {0, 1, 1}, profile);
    }

    /** One IFD entry before its value is placed: tag, type, count and the value's bytes. */
    private record Entry(int tag, int type, int count, byte[] value) {}

    /** "Exif\0\0" and a big-endian TIFF structure: IFD0 (with the EXIF IFD pointer when needed), then the EXIF IFD. */
    private static byte[] exif(Optional<Integer> orientation, Map<String, String> fields) {
        List<Entry> ifd0 = new ArrayList<>();
        List<Entry> sub = new ArrayList<>();
        orientation.ifPresent(value -> ifd0.add(new Entry(0x0112, 3, 1, u16(value))));
        for (Map.Entry<String, String> field : fields.entrySet()) {
            int[] tag = EXIF_TAGS.get(field.getKey());
            Entry entry = exifEntry(field.getKey(), tag[0], field.getValue());
            (tag[1] == 0 ? ifd0 : sub).add(entry);
        }
        if (!sub.isEmpty()) {
            ifd0.add(new Entry(0x8769, 4, 1, u32(0))); // offset patched below
        }
        ifd0.sort((a, b) -> Integer.compare(a.tag(), b.tag()));
        sub.sort((a, b) -> Integer.compare(a.tag(), b.tag()));
        int ifd0Offset = 8;
        int ifd0Size = 2 + 12 * ifd0.size() + 4;
        int subOffset = ifd0Offset + ifd0Size + dataSize(ifd0);
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.writeBytes(new byte[] {'M', 'M', 0, 0x2A});
        tiff.writeBytes(u32(ifd0Offset));
        List<Entry> patched = new ArrayList<>();
        for (Entry entry : ifd0) {
            patched.add(entry.tag() == 0x8769 ? new Entry(0x8769, 4, 1, u32(subOffset)) : entry);
        }
        tiff.writeBytes(ifd(patched, ifd0Offset));
        if (!sub.isEmpty()) {
            tiff.writeBytes(ifd(sub, subOffset));
        }
        return concat(ascii("Exif"), new byte[] {0, 0}, tiff.toByteArray());
    }

    private static Entry exifEntry(String name, int tag, String value) {
        switch (name) {
            case "ExposureTime", "FNumber", "FocalLength" -> {
                int slash = value.indexOf('/');
                if (slash < 0) {
                    throw new IllegalArgumentException(name + " is a fraction n/d: " + value);
                }
                long numerator = Long.parseLong(value.substring(0, slash));
                long denominator = Long.parseLong(value.substring(slash + 1));
                return new Entry(tag, 5, 1, concat(u32((int) numerator), u32((int) denominator)));
            }
            case "ISOSpeedRatings" -> {
                List<Integer> values = new ArrayList<>();
                int from = 0;
                while (from <= value.length()) {
                    int comma = value.indexOf(',', from);
                    int end = comma < 0 ? value.length() : comma;
                    values.add(Integer.parseInt(value.substring(from, end).trim()));
                    from = end + 1;
                }
                ByteArrayOutputStream shorts = new ByteArrayOutputStream();
                values.forEach(v -> shorts.writeBytes(u16(v)));
                return new Entry(tag, 3, values.size(), shorts.toByteArray());
            }
            case "UserComment" -> {
                byte[] bytes = concat(ascii("ASCII"), new byte[3], value.getBytes(StandardCharsets.US_ASCII));
                return new Entry(tag, 7, bytes.length, bytes);
            }
            case "Comments" -> {
                // PHP decodes the Windows XP tags of a big-endian ("MM") file as UCS-2 big-endian.
                byte[] bytes = concat(value.getBytes(StandardCharsets.UTF_16BE), new byte[] {0, 0});
                return new Entry(tag, 1, bytes.length, bytes);
            }
            default -> {
                byte[] bytes = concat(value.getBytes(StandardCharsets.UTF_8), new byte[] {0});
                return new Entry(tag, 2, bytes.length, bytes);
            }
        }
    }

    private static int dataSize(List<Entry> entries) {
        int size = 0;
        for (Entry entry : entries) {
            if (entry.value().length > 4) {
                size += entry.value().length + (entry.value().length % 2);
            }
        }
        return size;
    }

    /** The IFD at {@code offset} (from the TIFF header) followed by its out-of-line values. */
    private static byte[] ifd(List<Entry> entries, int offset) {
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int dataOffset = offset + 2 + 12 * entries.size() + 4;
        table.writeBytes(u16(entries.size()));
        for (Entry entry : entries) {
            table.writeBytes(u16(entry.tag()));
            table.writeBytes(u16(entry.type()));
            table.writeBytes(u32(entry.count()));
            if (entry.value().length <= 4) {
                byte[] inline = new byte[4];
                System.arraycopy(entry.value(), 0, inline, 0, entry.value().length);
                table.writeBytes(inline);
            } else {
                table.writeBytes(u32(dataOffset + data.size()));
                data.writeBytes(entry.value());
                if (entry.value().length % 2 == 1) {
                    data.write(0);
                }
            }
        }
        table.writeBytes(u32(0)); // no next IFD
        table.writeBytes(data.toByteArray());
        return table.toByteArray();
    }

    /** "Photoshop 3.0\0" and one 8BIM resource 0x0404 holding the IPTC datasets. */
    private static byte[] photoshopIptc(Map<Integer, List<String>> datasets) {
        List<String> charsetName = datasets.get(0);
        Charset charset = charsetName == null ? StandardCharsets.UTF_8 : Charset.forName(charsetName.get(0));
        ByteArrayOutputStream iptc = new ByteArrayOutputStream();
        if (charsetName == null) {
            iptc.writeBytes(new byte[] {0x1C, 1, 90, 0, 3, 0x1B, 0x25, 0x47}); // ESC % G: UTF-8
        }
        iptc.writeBytes(new byte[] {0x1C, 2, 0, 0, 2, 0, 4}); // record version 4
        for (Map.Entry<Integer, List<String>> dataset : datasets.entrySet()) {
            if (dataset.getKey() == 0) {
                continue;
            }
            for (String value : dataset.getValue()) {
                byte[] text = value.getBytes(charset);
                iptc.writeBytes(new byte[] {
                    0x1C, 2, (byte) (int) dataset.getKey(), (byte) (text.length >> 8), (byte) text.length
                });
                iptc.writeBytes(text);
            }
        }
        if (iptc.size() % 2 == 1) {
            iptc.write(0);
        }
        byte[] data = iptc.toByteArray();
        return concat(
                ascii("Photoshop 3.0"),
                new byte[] {0},
                ascii("8BIM"),
                new byte[] {0x04, 0x04, 0, 0},
                u32(data.length),
                data);
    }

    // ---- containers ------------------------------------------------------------------------------------------------

    private record ZipPart(String name, byte[] bytes, boolean stored) {}

    private static byte[] zip(List<ZipPart> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipPart part : parts) {
                ZipEntry entry = new ZipEntry(part.name());
                entry.setTime(1_600_000_000_000L);
                if (part.stored()) {
                    CRC32 crc = new CRC32();
                    crc.update(part.bytes());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(part.bytes().length);
                    entry.setCompressedSize(part.bytes().length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(part.bytes());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] isoMedia(String brand, String handler, byte[] minf, int w, int h, int seconds, int scale) {
        byte[] ftyp = box("ftyp", ascii(brand), u32(0x200), ascii(brand), ascii("isom"), ascii("mp42"));
        byte[] matrix = concat(
                u32(0x00010000), u32(0), u32(0), u32(0), u32(0x00010000), u32(0), u32(0), u32(0), u32(0x40000000));
        byte[] mvhd = fullBox(
                "mvhd",
                0,
                0,
                u32(0),
                u32(0),
                u32(1000),
                u32(seconds * 1000),
                u32(0x00010000),
                u16(0x0100),
                new byte[10],
                matrix,
                new byte[24],
                u32(2));
        byte[] tkhd = fullBox(
                "tkhd",
                0,
                3,
                u32(0),
                u32(0),
                u32(1),
                u32(0),
                u32(seconds * 1000),
                new byte[8],
                u16(0),
                u16(0),
                u16("soun".equals(handler) ? 0x0100 : 0),
                u16(0),
                matrix,
                u32(w << 16),
                u32(h << 16));
        byte[] mdhd = fullBox("mdhd", 0, 0, u32(0), u32(0), u32(scale), u32(seconds * scale), u16(0x55C4), u16(0));
        byte[] hdlr = fullBox("hdlr", 0, 0, u32(0), ascii(handler), new byte[12], ascii("qp"), new byte[] {0});
        byte[] trak = box("trak", tkhd, box("mdia", mdhd, hdlr, minf));
        byte[] moov = box("moov", mvhd, trak);
        return concat(ftyp, moov, box("mdat"));
    }

    private static byte[] dinf() {
        return box("dinf", fullBox("dref", 0, 0, u32(1), fullBox("url ", 0, 1)));
    }

    private static byte[] stbl(byte[] stsd, int duration) {
        return box(
                "stbl",
                stsd,
                fullBox("stts", 0, 0, u32(1), u32(1), u32(duration)),
                fullBox("stsc", 0, 0, u32(1), u32(1), u32(1), u32(1)),
                fullBox("stsz", 0, 0, u32(0), u32(1), u32(0)),
                fullBox("stco", 0, 0, u32(1), u32(0)));
    }

    private static byte[] box(String type, byte[]... content) {
        byte[] body = concat(content);
        return concat(u32(8 + body.length), ascii(type), body);
    }

    private static byte[] fullBox(String type, int version, int flags, byte[]... content) {
        return box(
                type,
                concat(
                        new byte[] {(byte) version, (byte) (flags >> 16), (byte) (flags >> 8), (byte) flags},
                        concat(content)));
    }

    /** An EBML element: the ID's own bytes, the size (one byte below 127, else eight), the content. */
    private static byte[] ebml(int id, byte[]... content) {
        byte[] body = concat(content);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bytes = id > 0xFFFFFF ? 4 : id > 0xFFFF ? 3 : id > 0xFF ? 2 : 1;
        for (int i = bytes - 1; i >= 0; i--) {
            out.write(id >>> (8 * i));
        }
        long size = body.length;
        if (size < 0x7F) {
            out.write(0x80 | (int) size);
        } else {
            out.write(0x01);
            for (int i = 6; i >= 0; i--) {
                out.write((int) (size >>> (8 * i)));
            }
        }
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] oggPage(byte[] packet, int headerType, long granule) {
        if (packet.length >= 255) {
            throw new IllegalArgumentException("one lacing segment only");
        }
        ByteBuffer page = ByteBuffer.allocate(27 + 1 + packet.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        page.put(ascii("OggS"))
                .put((byte) 0)
                .put((byte) headerType)
                .putLong(granule)
                .putInt(0x51500001);
        page.putInt(0).putInt(0).put((byte) 1).put((byte) packet.length).put(packet);
        byte[] bytes = page.array();
        int crc = oggCrc(bytes);
        bytes[22] = (byte) crc;
        bytes[23] = (byte) (crc >>> 8);
        bytes[24] = (byte) (crc >>> 16);
        bytes[25] = (byte) (crc >>> 24);
        return bytes;
    }

    /** Ogg's CRC-32: polynomial 0x04C11DB7, no reflection, initial value and final XOR 0. */
    private static int oggCrc(byte[] bytes) {
        int crc = 0;
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 24;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
        }
        return crc;
    }

    private static byte[] id3Frame(String id, byte[] content) {
        return concat(ascii(id), u32(content.length), new byte[] {0, 0}, content);
    }

    private static byte[] syncsafe(int size) {
        return new byte[] {
            (byte) ((size >> 21) & 0x7F),
            (byte) ((size >> 14) & 0x7F),
            (byte) ((size >> 7) & 0x7F),
            (byte) (size & 0x7F)
        };
    }

    private static byte[] pngChunk(String type, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(ascii(type));
        crc.update(data);
        return concat(u32(data.length), ascii(type), data, u32((int) crc.getValue()));
    }

    // ---- pixels ----------------------------------------------------------------------------------------------------

    private static BufferedImage gradient(int w, int h, int type) {
        if (w < 1 || h < 1) {
            throw new IllegalArgumentException("dimensions must be positive: " + w + "x" + h);
        }
        BufferedImage image = new BufferedImage(w, h, type);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.RED, w, h, Color.BLUE));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return image;
    }

    private static BufferedImage indexed(int w, int h, int frame) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(frame % 2 == 0 ? Color.RED : Color.BLUE);
        g.fillRect(w / 5, h / 6, w * 3 / 5, h * 2 / 3);
        g.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("no ImageIO writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void simpleCode(BitWriter bits, int... symbols) {
        bits.write(1, 1);
        bits.write(symbols.length - 1, 1);
        bits.write(1, 1);
        bits.write(symbols[0], 8);
        if (symbols.length == 2) {
            bits.write(symbols[1], 8);
        }
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

    // ---- bytes -----------------------------------------------------------------------------------------------------

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] u16(int value) {
        return new byte[] {(byte) (value >>> 8), (byte) value};
    }

    private static byte[] u32(int value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}

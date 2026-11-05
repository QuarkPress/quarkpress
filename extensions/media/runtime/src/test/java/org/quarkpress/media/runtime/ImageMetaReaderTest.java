package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.drew.lang.Rational;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wprestparity.MediaFixtures;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;

class ImageMetaReaderTest {

    private static final Path DIR = Path.of("target", "image-meta-test");
    private static final AtomicInteger COUNTER = new AtomicInteger();

    static Path write(byte[] bytes) throws IOException {
        Files.createDirectories(DIR);
        Path file = DIR.resolve("image-" + COUNTER.incrementAndGet() + ".tmp");
        Files.write(file, bytes);
        return file;
    }

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static List<String> fieldNames(ObjectNode meta) {
        List<String> names = new ArrayList<>();
        meta.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static ObjectNode jpegMeta(JpegMeta meta) throws IOException {
        return ImageMetaReader.read(write(MediaFixtures.jpeg(16, 12, meta)), "image/jpeg", "en_US");
    }

    private static String text(ObjectNode meta, String key) {
        return meta.get(key).textValue();
    }

    private static List<String> keywords(ObjectNode meta) {
        List<String> values = new ArrayList<>();
        for (JsonNode keyword : meta.get("keywords")) {
            values.add(keyword.textValue());
        }
        return values;
    }

    private static JpegMeta iptcHeadline(String headline) {
        return JpegMeta.none().withIptc(105, headline);
    }

    private static List<String> titleCaption(Map<String, String> exif) throws IOException {
        return titleCaption(JpegMeta.none(), exif);
    }

    private static List<String> titleCaption(JpegMeta base, Map<String, String> exif) throws IOException {
        JpegMeta meta = base;
        for (Map.Entry<String, String> field : new java.util.TreeMap<>(exif).entrySet()) {
            meta = meta.withExif(field.getKey(), field.getValue());
        }
        ObjectNode read = jpegMeta(meta);
        return List.of(text(read, "title"), text(read, "caption"));
    }

    /** An XMP packet with the alternative text in the element form, one {@code rdf:li} per language. */
    static String xmp(Map<String, String> byLanguage) {
        StringBuilder items = new StringBuilder();
        byLanguage.forEach((lang, text) -> items.append("<rdf:li xml:lang=\"")
                .append(lang)
                .append("\">")
                .append(text)
                .append("</rdf:li>"));
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF"
                + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\""
                + " xmlns:Iptc4xmpCore=\"http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/\">"
                + "<Iptc4xmpCore:AltTextAccessibility><rdf:Alt>" + items
                + "</rdf:Alt></Iptc4xmpCore:AltTextAccessibility></rdf:Description></rdf:RDF></x:xmpmeta>";
    }

    /** The alternative text as an attribute of the description, which WordPress does not read. */
    static String attributeFormXmp(String text) {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF"
                + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\""
                + " xmlns:Iptc4xmpCore=\"http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/\""
                + " Iptc4xmpCore:AltTextAccessibility=\"" + text + "\"/></rdf:RDF></x:xmpmeta>";
    }

    /** The alt that a JPEG holding {@code packet} gets for {@code locale}. */
    private static String alt(String packet, String locale) throws IOException {
        return text(
                ImageMetaReader.read(
                        write(MediaFixtures.jpeg(8, 8, JpegMeta.none().withXmp(packet))), "image/jpeg", locale),
                "alt");
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void defaultsAreThirteenStringKeysInOrder() throws IOException {
        ObjectNode meta = ImageMetaReader.read(write(MediaFixtures.jpeg(64, 48)), "image/jpeg", "en_US");
        assertThat(fieldNames(meta))
                .containsExactly(
                        "aperture",
                        "credit",
                        "camera",
                        "caption",
                        "created_timestamp",
                        "copyright",
                        "focal_length",
                        "iso",
                        "shutter_speed",
                        "title",
                        "orientation",
                        "keywords",
                        "alt");
        assertThat(meta.get("aperture").textValue()).isEqualTo("0");
        assertThat(meta.get("orientation").textValue()).isEqualTo("0");
        assertThat(meta.get("keywords").isArray()).isTrue();
        assertThat(meta.get("keywords")).isEmpty();
        for (String key : List.of("credit", "camera", "caption", "copyright", "title", "alt")) {
            assertThat(text(meta, key)).as(key).isEmpty();
        }
        for (String key : List.of("created_timestamp", "focal_length", "iso", "shutter_speed")) {
            assertThat(text(meta, key)).as(key).isEqualTo("0");
        }
    }

    @Test
    void exifFieldsOfAJpeg() throws IOException {
        ObjectNode meta = jpegMeta(JpegMeta.none()
                .withOrientation(6)
                .withExif("FNumber", "28/10")
                .withExif("Model", "X100V")
                .withExif("FocalLength", "50/1")
                .withExif("ISOSpeedRatings", "400")
                .withExif("ExposureTime", "1/250")
                .withExif("DateTimeDigitized", "2023:10:15 14:30:00")
                .withExif("DateTimeOriginal", "2020:01:01 00:00:00")
                .withExif("Artist", "Ann")
                .withExif("Copyright", "(c) Ann"));
        assertThat(text(meta, "aperture")).isEqualTo("2.8");
        assertThat(text(meta, "camera")).isEqualTo("X100V");
        assertThat(text(meta, "focal_length")).isEqualTo("50");
        assertThat(text(meta, "iso")).isEqualTo("400");
        assertThat(text(meta, "shutter_speed")).isEqualTo("0.004");
        assertThat(text(meta, "orientation")).isEqualTo("6");
        assertThat(text(meta, "created_timestamp")).isEqualTo("1697380200");
        assertThat(text(meta, "credit")).isEqualTo("Ann");
        assertThat(text(meta, "copyright")).isEqualTo("(c) Ann");

        assertThat(text(jpegMeta(JpegMeta.none().withExif("FNumber", "1775/1000")), "aperture"))
                .isEqualTo("1.78");
        assertThat(text(
                        jpegMeta(JpegMeta.none().withExif("DateTimeOriginal", "2020:01:01 00:00:00")),
                        "created_timestamp"))
                .isEqualTo("0");
        assertThat(text(jpegMeta(JpegMeta.none().withExif("ISOSpeedRatings", "100, 200")), "iso"))
                .isEqualTo("100");
        assertThat(text(jpegMeta(JpegMeta.none().withExif("ExposureTime", "1/60")), "shutter_speed"))
                .isEqualTo("0.016666666666667");
        assertThat(text(jpegMeta(JpegMeta.none().withExif("ExposureTime", "1/100000")), "shutter_speed"))
                .isEqualTo("1.0E-5");
        assertThat(text(jpegMeta(JpegMeta.none().withExif("FocalLength", "1/3")), "focal_length"))
                .isEqualTo("0.33333333333333");
        // a date in another form does not parse and is stored as an empty text
        assertThat(text(
                        jpegMeta(JpegMeta.none().withExif("DateTimeDigitized", "2023-10-15 14:30:00")),
                        "created_timestamp"))
                .isEmpty();
    }

    @Test
    void exifIsReadOnlyForJpeg() throws IOException {
        Path file = write(MediaFixtures.jpeg(
                16, 12, JpegMeta.none().withExif("Model", "X100V").withIptc(105, "Head")));
        ObjectNode meta = ImageMetaReader.read(file, "image/png", "en_US");
        assertThat(text(meta, "camera")).isEmpty();
        assertThat(text(meta, "title")).isEmpty();
    }

    @Test
    void iptcComesFirst() throws IOException {
        ObjectNode meta = jpegMeta(JpegMeta.none()
                .withIptc(105, "Sunset at S" + cp(0xE8) + "te")
                .withIptc(120, "Taken from the pier, 2025")
                .withIptc(110, "Credit")
                .withIptc(80, "By-line")
                .withIptc(116, "(c) Ann")
                .withIptc(25, "sea", " sun ")
                .withExif("ImageDescription", "Sunset")
                .withExif("Artist", "Bob")
                .withExif("Copyright", "(c) Bob"));
        assertThat(text(meta, "title")).isEqualTo("Sunset at S" + cp(0xE8) + "te");
        assertThat(text(meta, "caption")).isEqualTo("Taken from the pier, 2025");
        assertThat(text(meta, "credit")).isEqualTo("Credit");
        assertThat(text(meta, "copyright")).isEqualTo("(c) Ann");
        assertThat(keywords(meta)).containsExactly("sea", " sun ");

        assertThat(text(jpegMeta(JpegMeta.none().withIptc(5, "Object")), "title"))
                .isEqualTo("Object");
        assertThat(text(jpegMeta(JpegMeta.none().withIptc(80, "By-line")), "credit"))
                .isEqualTo("By-line");
        String caption79 = "c".repeat(79);
        String caption80 = "c".repeat(80);
        ObjectNode short79 = jpegMeta(JpegMeta.none().withIptc(120, caption79));
        assertThat(List.of(text(short79, "title"), text(short79, "caption"))).containsExactly(caption79, caption79);
        ObjectNode long80 = jpegMeta(JpegMeta.none().withIptc(120, caption80));
        assertThat(List.of(text(long80, "title"), text(long80, "caption"))).containsExactly("", caption80);
        // the limit counts bytes: 40 two-byte characters are 80 bytes
        String twoByte = cp(0xE9).repeat(40);
        assertThat(text(jpegMeta(JpegMeta.none().withIptc(120, twoByte)), "title"))
                .isEmpty();

        assertThat(text(
                        jpegMeta(JpegMeta.none().withIptc(55, "20231015").withIptc(60, "143000+0200")),
                        "created_timestamp"))
                .isEqualTo("1697373000");
        assertThat(text(jpegMeta(JpegMeta.none().withIptc(55, "20231015").withIptc(60, "143000")), "created_timestamp"))
                .isEqualTo("1697380200");
        assertThat(text(jpegMeta(JpegMeta.none().withIptc(55, "20231015")), "created_timestamp"))
                .isEqualTo("0");
        // an IPTC date wins over the EXIF one
        assertThat(text(
                        jpegMeta(JpegMeta.none()
                                .withIptc(55, "20231015")
                                .withIptc(60, "143000+0200")
                                .withExif("DateTimeDigitized", "2020:01:01 00:00:00")),
                        "created_timestamp"))
                .isEqualTo("1697373000");
        // keywords only when the first is non-empty; "0" is empty to PHP
        assertThat(keywords(jpegMeta(JpegMeta.none().withIptc(25, "0", "x")))).isEmpty();
        assertThat(text(jpegMeta(JpegMeta.none().withIptc(105, "0").withIptc(5, "Object")), "title"))
                .isEqualTo("Object");
    }

    @Test
    void exifTitleAndCaptionFallbacks() throws IOException {
        assertThat(titleCaption(Map.of("ImageDescription", "Sunset"))).containsExactly("Sunset", "Sunset");
        assertThat(titleCaption(Map.of("ImageDescription", "Sunset", "UserComment", "Shot at Malibu")))
                .containsExactly("Sunset", "Shot at Malibu");
        assertThat(titleCaption(iptcHeadline("Beach"), Map.of("ImageDescription", "Sunset", "UserComment", "Malibu")))
                .containsExactly("Beach", "Sunset Malibu");
        assertThat(titleCaption(Map.of("ImageDescription", "x".repeat(120)))).containsExactly("", "x".repeat(120));
        assertThat(titleCaption(Map.of("ImageDescription", "OLYMPUS DIGITAL CAMERA")))
                .containsExactly("OLYMPUS DIGITAL CAMERA", "OLYMPUS DIGITAL CAMERA");
        assertThat(titleCaption(Map.of("UserComment", "Only the comment")))
                .containsExactly("Only the comment", "Only the comment");
        assertThat(titleCaption(Map.of("ImageDescription", "Same text", "UserComment", "Same text")))
                .containsExactly("Same text", "Same text");
        assertThat(titleCaption(Map.of("ImageDescription", "d".repeat(80), "UserComment", "U")))
                .containsExactly("", "d".repeat(80) + " U");
        assertThat(titleCaption(Map.of("Comments", "From Windows"))).containsExactly("", "From Windows");
        assertThat(titleCaption(Map.of("ImageDescription", "  padded  "))).containsExactly("padded", "padded");
    }

    @Test
    void xmpAltPicksTheLocaleThenItsLanguageThenDefault() throws IOException {
        assertThat(alt(xmp(ordered("en-US", "A", "x-default", "B")), "en_US")).isEqualTo("B");
        assertThat(alt(xmp(ordered("en", "C", "x-default", "B")), "en_US")).isEqualTo("C");
        assertThat(alt(xmp(ordered("x-default", "B", "en", "C")), "en_US")).isEqualTo("C");
        assertThat(alt(xmp(ordered("en_US", "F", "en", "C")), "en_US")).isEqualTo("F");
        assertThat(alt(xmp(ordered("de", "D", "x-default", "B")), "de")).isEqualTo("D");
        assertThat(alt(xmp(ordered("fr", "G")), "en_US")).isEqualTo("");
        assertThat(alt(xmp(ordered("x-default", "  spaced &amp; &lt;b&gt;bold&lt;/b&gt;  ")), "en_US"))
                .isEqualTo("  spaced &amp; <b>bold</b>  ");
        assertThat(alt(attributeFormXmp("E"), "en_US")).isEqualTo("");
        assertThat(alt("<x:xmpmeta broken", "en_US")).isEqualTo("");
        assertThat(alt("<!DOCTYPE x [<!ENTITY e \"boom\">]>" + xmp(ordered("x-default", "&e;")), "en_US"))
                .isEqualTo("");
        assertThat(ImageMetaReader.read(
                                write(MediaFixtures.png(8, 8, Optional.of(xmp(Map.of("x-default", "A red kite"))))),
                                "image/png",
                                "en_US")
                        .get("alt")
                        .textValue())
                .isEqualTo("A red kite");
    }

    @Test
    void xmpPacketIsTheFirstStartToTheFirstEnd() {
        assertThat(ImageMetaReader.xmpAlt("".getBytes(StandardCharsets.UTF_8), "en_US"))
                .isEmpty();
        String packet = xmp(Map.of("x-default", "kite"));
        assertThat(ImageMetaReader.xmpAlt(packet.getBytes(StandardCharsets.UTF_8), "en_US"))
                .isEqualTo("kite");
        // a document type declaration is refused before the packet starts, so it cannot appear inside it
        assertThat(ImageMetaReader.xmpAlt(
                        ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><!DOCTYPE z><y/></x:xmpmeta>")
                                .getBytes(StandardCharsets.UTF_8),
                        "en_US"))
                .isEmpty();
    }

    @Test
    void latin1IsRepairedAndKsesApplies() throws IOException {
        ObjectNode latin = jpegMeta(JpegMeta.none()
                .withIptc(0, "ISO-8859-1")
                .withIptc(120, "Caf" + cp(0xE9))
                .withIptc(116, cp(0xA9) + " i04")
                .withIptc(25, "caf" + cp(0xE9), "na" + cp(0xEF) + "ve"));
        assertThat(text(latin, "caption")).isEqualTo("Caf" + cp(0xE9));
        assertThat(text(latin, "copyright")).isEqualTo(cp(0xA9) + " i04");
        assertThat(keywords(latin)).containsExactly("caf" + cp(0xE9), "na" + cp(0xEF) + "ve");

        ObjectNode html = jpegMeta(JpegMeta.none().withIptc(120, "<b>x</b> & y <script>z</script>"));
        assertThat(text(html, "caption")).isEqualTo("<b>x</b> &amp; y z");
        ObjectNode exifHtml =
                jpegMeta(JpegMeta.none().withExif("ImageDescription", "<b>bold</b> & co <script>x</script>"));
        assertThat(text(exifHtml, "caption")).isEqualTo("<b>bold</b> &amp; co x");
        assertThat(ImageMetaReader.repaired(new byte[] {'C', 'a', 'f', (byte) 0xC3, (byte) 0xA9}))
                .isEqualTo("Caf" + cp(0xE9));
    }

    @Test
    void windowsCommentsAreReadAsPhpConvertsThem() throws IOException {
        // UCS-2 to ISO-8859-15 (the euro sign is byte A4, which the repair reads as the currency sign; Cyrillic is "?")
        ObjectNode meta = jpegMeta(
                JpegMeta.none().withExif("Comments", cp(0xE9) + "t" + cp(0xE9) + " " + cp(0x20AC) + " " + cp(0x424)));
        assertThat(text(meta, "caption")).isEqualTo(cp(0xE9) + "t" + cp(0xE9) + " " + cp(0xA4) + " ?");
    }

    @Test
    void phpTrimAndTruthiness() {
        assertThat(ImageMetaReader.trim(new byte[] {0, ' ', 'a', '\t', 0x0B})).containsExactly('a');
        assertThat(ImageMetaReader.trim(" \u00a0a\u00a0 ")).isEqualTo("\u00a0a\u00a0");
        assertThat(ImageMetaReader.nonEmpty(new byte[] {'0'})).isFalse();
        assertThat(ImageMetaReader.nonEmpty(new byte[] {'0', '0'})).isTrue();
        assertThat(ImageMetaReader.nonEmpty(new byte[0])).isFalse();
    }

    @Test
    void anUnreadableJpegGivesTheDefaults() throws IOException {
        ObjectNode meta = ImageMetaReader.read(write(MediaFixtures.text("not a jpeg")), "image/jpeg", "en_US");
        assertThat(fieldNames(meta)).hasSize(13);
        assertThat(text(meta, "title")).isEmpty();
    }

    // ---- fix round: hostile input, structure and the rules each branch pins -------------------------------------

    @Test
    void aPngBombGivesTheDefaultsQuickly() throws IOException {
        Path bomb = write(ImageHeadersTest.pngBomb("zTXt"));
        ObjectNode meta = assertTimeoutPreemptively(
                ImageHeadersTest.QUICK, () -> ImageMetaReader.read(bomb, "image/png", "en_US"));
        assertThat(text(meta, "alt")).isEmpty();
        Path huge = write(ImageHeadersTest.pngHugeDeclaredChunk());
        assertThat(text(
                        assertTimeoutPreemptively(
                                ImageHeadersTest.QUICK, () -> ImageMetaReader.read(huge, "image/png", "en_US")),
                        "title"))
                .isEmpty();
    }

    /** A packet whose alternative text sits under {@code depth} nested elements. */
    static String deepXmp(int depth) {
        return xmp(Map.of("x-default", "<b>".repeat(depth) + "deep" + "</b>".repeat(depth)));
    }

    @Test
    void deeplyNestedXmpIsRefusedWithoutAnError() {
        // libxml2, which PHP's DOM uses, refuses documents nested deeper than 256 elements
        assertThat(ImageMetaReader.xmpAlt(deepXmp(200).getBytes(StandardCharsets.UTF_8), "en_US"))
                .isEqualTo("deep");
        assertThat(ImageMetaReader.xmpAlt(deepXmp(100_000).getBytes(StandardCharsets.UTF_8), "en_US"))
                .isEmpty();
    }

    @Test
    void xmpStructure() {
        String rdf = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
        String good = xmp(Map.of("x-default", "kite"));
        // the element must be in the IPTC Core namespace, not only carry its local name
        String otherNamespace = good.replace("http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/", "urn:other");
        assertThat(ImageMetaReader.xmpAlt(otherNamespace.getBytes(StandardCharsets.UTF_8), "en_US"))
                .isEmpty();
        String otherRoot = good.replace("adobe:ns:meta/", "urn:other");
        assertThat(ImageMetaReader.xmpAlt(otherRoot.getBytes(StandardCharsets.UTF_8), "en_US"))
                .isEmpty();
        // the first description holding the element wins
        String two = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"" + rdf + "\""
                + " xmlns:Iptc4xmpCore=\"http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/\">"
                + "<rdf:Description rdf:about=\"\"/>"
                + description("first") + description("second") + "</rdf:RDF></x:xmpmeta>";
        assertThat(ImageMetaReader.xmpAlt(two.getBytes(StandardCharsets.UTF_8), "en_US"))
                .isEqualTo("first");
        // an empty locale matches no empty language tag
        String emptyLang = xmp(ordered("", "E", "x-default", "B"));
        assertThat(ImageMetaReader.xmpAlt(emptyLang.getBytes(StandardCharsets.UTF_8), ""))
                .isEqualTo("B");
        assertThat(ImageMetaReader.xmpAlt(emptyLang.getBytes(StandardCharsets.UTF_8), null))
                .isEqualTo("B");
    }

    private static String description(String alt) {
        return "<rdf:Description rdf:about=\"\"><Iptc4xmpCore:AltTextAccessibility><rdf:Alt>"
                + "<rdf:li xml:lang=\"x-default\">" + alt + "</rdf:li></rdf:Alt></Iptc4xmpCore:AltTextAccessibility>"
                + "</rdf:Description>";
    }

    @Test
    void thePacketIsFoundOnlyInOrder() throws IOException {
        String packet = xmp(Map.of("x-default", "kite"));
        byte[] endFirst = ("</x:xmpmeta>" + packet.replace("</x:xmpmeta>", "")).getBytes(StandardCharsets.UTF_8);
        assertThat(ImageMetaReader.xmpPacket(write(endFirst))).isEmpty();
        // the search covers the first MiB only
        byte[] bytes = packet.getBytes(StandardCharsets.UTF_8);
        byte[] inside = new byte[ImageMetaReader.XMP_LIMIT];
        System.arraycopy(bytes, 0, inside, inside.length - bytes.length, bytes.length);
        assertThat(ImageMetaReader.xmpPacket(write(inside))).isEqualTo(bytes);
        byte[] beyond = new byte[ImageMetaReader.XMP_LIMIT + bytes.length];
        System.arraycopy(bytes, 0, beyond, ImageMetaReader.XMP_LIMIT - 10, bytes.length);
        assertThat(ImageMetaReader.xmpPacket(write(beyond))).isEmpty();
    }

    @Test
    void keywordsAreCapped() throws IOException {
        String[] many = new String[ImageMetaReader.MAX_KEYWORDS + 1];
        for (int i = 0; i < many.length; i++) {
            many[i] = "k" + i;
        }
        List<String> kept = keywords(jpegMeta(JpegMeta.none().withIptc(25, many)));
        assertThat(kept).hasSize(ImageMetaReader.MAX_KEYWORDS).startsWith("k0").endsWith("k999");
    }

    @Test
    void iptcPrecedence() throws IOException {
        ObjectNode both = jpegMeta(JpegMeta.none().withIptc(5, "Object").withIptc(105, "Headline"));
        assertThat(text(both, "title")).isEqualTo("Headline");
        ObjectNode credit = jpegMeta(JpegMeta.none().withIptc(80, "By-line").withIptc(110, "Credit"));
        assertThat(text(credit, "credit")).isEqualTo("Credit");
        // with a headline as the title, D = U gives D once
        assertThat(titleCaption(iptcHeadline("H"), Map.of("ImageDescription", "Same", "UserComment", "Same")))
                .containsExactly("H", "Same");
        // a title from the user comment is subject to the same 80-byte limit
        assertThat(titleCaption(Map.of("UserComment", "u".repeat(79)))).containsExactly("u".repeat(79), "u".repeat(79));
        assertThat(titleCaption(Map.of("UserComment", "u".repeat(80)))).containsExactly("", "u".repeat(80));
    }

    private static ObjectNode exif(ExifIFD0Directory ifd0, ExifSubIFDDirectory sub, boolean bigEndian) {
        ImageMetaReader.Fields fields = new ImageMetaReader.Fields();
        ImageMetaReader.readExif(ifd0, sub, bigEndian, fields);
        return fields.toJson("");
    }

    private static short[] ucs2(String text, boolean bigEndian) {
        byte[] bytes = (text + "\0").getBytes(bigEndian ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE);
        short[] units = new short[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            units[i] = (short) (bytes[i] & 0xFF);
        }
        return units;
    }

    @Test
    void exifBranches() {
        ExifIFD0Directory author = new ExifIFD0Directory();
        author.setObject(ExifDirectoryBase.TAG_WIN_AUTHOR, ucs2("Ann", true));
        assertThat(text(exif(author, null, true), "credit")).isEqualTo("Ann");
        for (boolean bigEndian : new boolean[] {true, false}) {
            ExifIFD0Directory comments = new ExifIFD0Directory();
            comments.setObject(ExifDirectoryBase.TAG_WIN_COMMENT, ucs2("Hi " + cp(0xE9), bigEndian));
            assertThat(text(exif(comments, null, bigEndian), "caption"))
                    .as("big endian " + bigEndian)
                    .isEqualTo("Hi " + cp(0xE9));
        }
        ExifSubIFDDirectory list = new ExifSubIFDDirectory();
        list.setRationalArray(ExifDirectoryBase.TAG_FNUMBER, new Rational[] {new Rational(28, 10), new Rational(4, 1)});
        list.setRationalArray(
                ExifDirectoryBase.TAG_FOCAL_LENGTH, new Rational[] {new Rational(50, 1), new Rational(4, 1)});
        list.setInt(ExifDirectoryBase.TAG_ISO_EQUIVALENT, 0);
        ObjectNode listed = exif(null, list, true);
        assertThat(text(listed, "aperture")).isEqualTo("0");
        assertThat(text(listed, "focal_length")).isEqualTo("0");
        assertThat(text(listed, "iso")).isEqualTo("0");
        for (boolean bigEndian : new boolean[] {true, false}) {
            ExifSubIFDDirectory unicode = new ExifSubIFDDirectory();
            byte[] text = (cp(0xE9) + cp(0x20AC) + cp(0x424) + "a\0b")
                    .getBytes(bigEndian ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE);
            unicode.setByteArray(
                    ExifDirectoryBase.TAG_USER_COMMENT,
                    ImageHeadersTest.concat("UNICODE\0".getBytes(StandardCharsets.ISO_8859_1), text));
            // converted to ISO-8859-15 as PHP does (euro A4, Cyrillic "?"), a NUL kept until kses removes it; the
            // repair reads A4 as Latin-1
            assertThat(text(exif(null, unicode, bigEndian), "caption")).isEqualTo(cp(0xE9) + cp(0xA4) + "?ab");
        }
    }

    /** A JPEG whose UserComment holds exactly these 16 bytes (character code and text). */
    private static byte[] userComment(String sixteen) {
        byte[] jpeg = MediaFixtures.jpeg(8, 8, JpegMeta.none().withExif("UserComment", "XXXXXXXX"));
        byte[] marker = "ASCII\0\0\0XXXXXXXX".getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i + marker.length <= jpeg.length; i++) {
            if (Arrays.equals(jpeg, i, i + marker.length, marker, 0, marker.length)) {
                System.arraycopy(sixteen.getBytes(StandardCharsets.ISO_8859_1), 0, jpeg, i, 16);
                return jpeg;
            }
        }
        throw new IllegalStateException("no user comment in the fixture");
    }

    @Test
    void userCommentCharacterCodesAsPhpReadsThem() throws IOException {
        // observed with PHP 8.4's exif_read_data on these bytes
        assertThat(caption(userComment("ASCII\0\0\0ab\0cdefg"))).isEqualTo("ab");
        assertThat(caption(userComment("\0\0\0\0\0\0\0\0ab\0cdefg"))).isEqualTo("ab");
        assertThat(caption(userComment("\0\0\0\0\0\0\0\0Undef123"))).isEqualTo("Undef123");
        // JIS text keeps its NUL, which kses then removes
        assertThat(caption(userComment("JIS\0\0\0\0\0ab\0cdefg"))).isEqualTo("abcdefg");
        assertThat(caption(userComment("JIS\0\0\0\0\0Kanji123"))).isEqualTo("Kanji123");
        assertThat(caption(userComment("FOO\0\0\0\0\0Unkn1234"))).isEqualTo("FOO");
    }

    private static String caption(byte[] jpeg) throws IOException {
        return text(ImageMetaReader.read(write(jpeg), "image/jpeg", "en_US"), "caption");
    }

    // ---- fix round T9d -------------------------------------------------------------------------------------------

    @Test
    void theDepthLimitIsLibxmlsTwoHundredFiftySix() {
        // the packet's own path holds six elements above the nested ones
        assertThat(ImageMetaReader.xmpAlt(deepXmp(249).getBytes(StandardCharsets.UTF_8), "en_US"))
                .isEqualTo("deep");
        assertThat(ImageMetaReader.xmpAlt(deepXmp(250).getBytes(StandardCharsets.UTF_8), "en_US"))
                .isEqualTo("deep");
        assertThat(ImageMetaReader.xmpAlt(deepXmp(251).getBytes(StandardCharsets.UTF_8), "en_US"))
                .isEmpty();
    }

    @Test
    void theTextOfAnItemIncludesTextAfterChildElements() {
        String mixed = xmp(Map.of("x-default", "a<b>b</b>c"));
        assertThat(ImageMetaReader.xmpAlt(mixed.getBytes(StandardCharsets.UTF_8), "en_US"))
                .isEqualTo("abc");
        String nested = xmp(Map.of("x-default", "x<b>y<i>z</i></b>w<![CDATA[v]]>"));
        assertThat(ImageMetaReader.xmpAlt(nested.getBytes(StandardCharsets.UTF_8), "en_US"))
                .isEqualTo("xyzwv");
    }

    /** One Photoshop image resource: signature, id, padded Pascal name, size and padded data. */
    private static byte[] resource(int id, String name, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("8BIM".getBytes(StandardCharsets.ISO_8859_1));
        out.write(id >> 8);
        out.write(id);
        out.write(name.length());
        out.writeBytes(name.getBytes(StandardCharsets.ISO_8859_1));
        if ((name.length() + 1) % 2 == 1) {
            out.write(0);
        }
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(data);
        if (data.length % 2 == 1) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static final byte[] IPTC_HI = {0x1C, 2, 105, 0, 2, 'H', 'i'};

    private static byte[] photoshop(byte[]... resources) {
        return ImageHeadersTest.concat(
                "Photoshop 3.0\0".getBytes(StandardCharsets.ISO_8859_1), ImageHeadersTest.concat(resources));
    }

    @Test
    void photoshopResourcesArePaddedToEvenSizes() {
        byte[] odd = photoshop(resource(0x03ED, "", new byte[3]), resource(0x0404, "", IPTC_HI));
        assertThat(ImageMetaReader.iptcResource(List.of(odd))).containsExactly(IPTC_HI);
        byte[] named = photoshop(resource(0x03ED, "ab", new byte[2]), resource(0x0404, "c", IPTC_HI));
        assertThat(ImageMetaReader.iptcResource(List.of(named))).containsExactly(IPTC_HI);
        byte[] notPhotoshop = resource(0x0404, "", IPTC_HI);
        assertThat(ImageMetaReader.iptcResource(List.of(notPhotoshop, odd))).containsExactly(IPTC_HI);
    }

    @Test
    void photoshopResourcesAreWalkedUpToTheLimit() {
        byte[] filler = resource(0x03ED, "", new byte[2]);
        byte[][] within = new byte[ImageMetaReader.MAX_RESOURCES][];
        Arrays.fill(within, filler);
        within[within.length - 1] = resource(0x0404, "", IPTC_HI);
        assertThat(ImageMetaReader.iptcResource(List.of(photoshop(within)))).containsExactly(IPTC_HI);
        byte[][] beyond = Arrays.copyOf(within, within.length + 1);
        beyond[beyond.length - 2] = filler;
        beyond[beyond.length - 1] = resource(0x0404, "", IPTC_HI);
        assertThat(ImageMetaReader.iptcResource(List.of(photoshop(beyond)))).isNull();
    }

    /**
     * An EXIF APP1 payload (big-endian) whose IFD0 holds Model and, when asked, an XMP packet (0x02BC) and an IPTC
     * block (0x83BB) as embedded documents.
     */
    static byte[] exifPayload(String model, boolean embedded) {
        byte[] modelBytes = (model + "\0").getBytes(StandardCharsets.ISO_8859_1);
        byte[] xmpBytes = xmp(Map.of("x-default", "inner")).getBytes(StandardCharsets.UTF_8);
        int entries = embedded ? 3 : 1;
        int dataAt = 8 + 2 + 12 * entries + 4;
        ByteBuffer ifd = ByteBuffer.allocate(dataAt + 64 + xmpBytes.length + 2 * IPTC_HI.length);
        ifd.put(new byte[] {'M', 'M', 0, 0x2A}).putInt(8).putShort((short) entries);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ifd.putShort((short) 0x0110).putShort((short) 2).putInt(modelBytes.length);
        if (modelBytes.length <= 4) {
            ifd.put(Arrays.copyOf(modelBytes, 4));
        } else {
            ifd.putInt(dataAt);
            data.writeBytes(modelBytes);
            if (data.size() % 2 == 1) {
                data.write(0);
            }
        }
        if (embedded) {
            ifd.putShort((short) 0x02BC)
                    .putShort((short) 1)
                    .putInt(xmpBytes.length)
                    .putInt(dataAt + data.size());
            data.writeBytes(xmpBytes);
            if (data.size() % 2 == 1) {
                data.write(0);
            }
            ifd.putShort((short) 0x83BB)
                    .putShort((short) 7)
                    .putInt(IPTC_HI.length)
                    .putInt(dataAt + data.size());
            data.writeBytes(IPTC_HI);
        }
        ifd.putInt(0);
        ifd.put(data.toByteArray());
        byte[] tiff = Arrays.copyOf(ifd.array(), ifd.position());
        return ImageHeadersTest.concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), tiff);
    }

    @Test
    void embeddedExifDocumentsAreNeverParsed() {
        Metadata exif = ImageMetaReader.exifMetadata(exifPayload("X", true));
        assertThat(exif.getFirstDirectoryOfType(ExifIFD0Directory.class).getString(ExifDirectoryBase.TAG_MODEL))
                .isEqualTo("X");
        assertThat(exif.getDirectoriesOfType(IptcDirectory.class)).isEmpty();
        assertThat(exif.getDirectoriesOfType(XmpDirectory.class)).isEmpty();
    }

    private static byte[] app1(byte[] payload) {
        return ImageHeadersTest.concat(
                new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) ((payload.length + 2) >> 8), (byte) (payload.length + 2)},
                payload);
    }

    @Test
    void onlyTheFirstExifSegmentIsRead() throws IOException {
        byte[] plain = MediaFixtures.jpeg(8, 8);
        int insertAt = 4 + (((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF));
        byte[] xmpSegment = app1(ImageHeadersTest.concat(
                "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1),
                xmp(Map.of("x-default", "kite")).getBytes(StandardCharsets.UTF_8)));
        byte[] jpeg = ImageHeadersTest.concat(
                Arrays.copyOf(plain, insertAt),
                xmpSegment,
                app1(exifPayload("First", true)),
                app1(exifPayload("Second", false)),
                Arrays.copyOfRange(plain, insertAt, plain.length));
        ObjectNode meta = ImageMetaReader.read(write(jpeg), "image/jpeg", "en_US");
        assertThat(text(meta, "camera")).isEqualTo("First");
        // the IPTC block inside EXIF is not WordPress's IPTC
        assertThat(text(meta, "title")).isEmpty();
        assertThat(text(meta, "alt")).isEqualTo("kite");
    }
}

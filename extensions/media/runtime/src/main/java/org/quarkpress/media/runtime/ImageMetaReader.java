package org.quarkpress.media.runtime;

import com.drew.imaging.jpeg.JpegProcessingException;
import com.drew.imaging.jpeg.JpegSegmentData;
import com.drew.imaging.jpeg.JpegSegmentReader;
import com.drew.imaging.jpeg.JpegSegmentType;
import com.drew.imaging.tiff.TiffProcessingException;
import com.drew.imaging.tiff.TiffReader;
import com.drew.lang.ByteArrayReader;
import com.drew.lang.RandomAccessReader;
import com.drew.lang.Rational;
import com.drew.lang.SequentialByteArrayReader;
import com.drew.lang.StreamReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.StringValue;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifTiffHandler;
import com.drew.metadata.iptc.IptcDirectory;
import com.drew.metadata.iptc.IptcReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.quarkpress.lib.wpkses.Kses;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * An image's {@code image_meta} as WordPress stores it: the thirteen keys {@code aperture}, {@code credit},
 * {@code camera}, {@code caption}, {@code created_timestamp}, {@code copyright}, {@code focal_length}, {@code iso},
 * {@code shutter_speed}, {@code title}, {@code orientation}, {@code keywords} and {@code alt}, every value a string
 * ({@code keywords} a list of strings), defaults {@code "0"}, {@code ""} and {@code []}.
 *
 * <p>IPTC (the APP13 segment) is read first and wins; EXIF fills what is still empty and gives the camera numbers.
 * Both are read for JPEG files only. The alternative text comes from the XMP packet of any file. Texts that are not
 * valid UTF-8 are read as ISO-8859-1, and every value passes WordPress's post HTML filter.
 */
public final class ImageMetaReader {

    /** Titles are taken from captions and descriptions shorter than this many bytes. */
    static final int TITLE_BYTES = 80;

    /** Most bytes of a file searched for the XMP packet. */
    static final int XMP_LIMIT = 1024 * 1024;

    /** Most keywords kept. */
    static final int MAX_KEYWORDS = 1000;

    /** Most bytes of a JPEG read for its metadata segments. */
    static final int JPEG_LIMIT = ImageHeaders.HEADER_LIMIT;

    static final String XMP_META = "adobe:ns:meta/";
    static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    static final String IPTC_CORE = "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/";
    static final String XML = "http://www.w3.org/XML/1998/namespace";

    private static final byte[] XMP_START = ascii("<x:xmpmeta");
    private static final byte[] XMP_END = ascii("</x:xmpmeta>");
    private static final byte[] EMPTY = new byte[0];
    private static final Charset ISO_8859_15 = Charset.forName("ISO-8859-15");
    private static final Charset ISO_2022_JP = Charset.forName("ISO-2022-JP");
    private static final byte[] EXIF_PREAMBLE = ascii("Exif\0\0");
    private static final byte[] PHOTOSHOP_PREAMBLE = ascii("Photoshop 3.0\0");

    /** Most Photoshop resource blocks walked in an APP13 segment. */
    static final int MAX_RESOURCES = 1024;

    /** Deepest element nesting the XMP parser accepts, as libxml2's default. */
    static final int MAX_XML_DEPTH = 256;

    private static final String MAX_ELEMENT_DEPTH = "http://www.oracle.com/xml/jaxp/properties/maxElementDepth";

    private ImageMetaReader() {}

    /**
     * The {@code image_meta} of {@code file}.
     *
     * @param file the image file
     * @param mimeType the file's image type; IPTC and EXIF are read only for {@code image/jpeg}
     * @param locale the site's locale in WordPress's form ({@code en_US}), which picks the alternative text's language
     * @throws IOException when the file cannot be read
     */
    public static ObjectNode read(Path file, String mimeType, String locale) throws IOException {
        Fields fields = new Fields();
        if ("image/jpeg".equals(mimeType)) {
            readJpeg(file, fields);
        }
        String alt = xmpAlt(xmpPacket(file), locale);
        return fields.toJson(alt);
    }

    /**
     * The values as PHP holds them while WordPress reads: texts as bytes, numbers as {@link Long} or {@link Double}.
     */
    static final class Fields {
        Object aperture = 0L;
        byte[] credit = EMPTY;
        byte[] camera = EMPTY;
        byte[] caption = EMPTY;
        /** A number, or {@link Boolean#FALSE} when a date was present but did not parse. */
        Object createdTimestamp = 0L;

        byte[] copyright = EMPTY;
        Object focalLength = 0L;
        byte[] iso = ascii("0");
        Object shutterSpeed = 0L;
        byte[] title = EMPTY;
        Object orientation = 0L;
        List<byte[]> keywords = List.of();

        ObjectNode toJson(String alt) {
            Kses kses = Kses.wordPress();
            ObjectNode meta = JsonNodeFactory.instance.objectNode();
            meta.put("aperture", kses.post(ExifNumbers.text(aperture)));
            meta.put("credit", kses.post(repaired(credit)));
            meta.put("camera", kses.post(repaired(camera)));
            meta.put("caption", kses.post(repaired(caption)));
            meta.put("created_timestamp", kses.post(createdTimestamp instanceof Number n ? ExifNumbers.text(n) : ""));
            meta.put("copyright", kses.post(repaired(copyright)));
            meta.put("focal_length", kses.post(ExifNumbers.text(focalLength)));
            meta.put("iso", kses.post(repaired(iso)));
            meta.put("shutter_speed", kses.post(ExifNumbers.text(shutterSpeed)));
            meta.put("title", kses.post(repaired(title)));
            meta.put("orientation", kses.post(ExifNumbers.text(orientation)));
            ArrayNode list = meta.putArray("keywords");
            for (byte[] keyword : keywords) {
                list.add(kses.post(repaired(keyword)));
            }
            meta.put("alt", kses.post(alt));
            return meta;
        }

        private boolean createdIsEmpty() {
            return Boolean.FALSE.equals(createdTimestamp)
                    || (createdTimestamp instanceof Number n && n.doubleValue() == 0);
        }
    }

    // ---- JPEG: IPTC, then EXIF -------------------------------------------------------------------------------------

    private static void readJpeg(Path file, Fields fields) throws IOException {
        JpegSegmentData segments;
        try (InputStream in = new ImageHeaders.Bounded(Files.newInputStream(file), JPEG_LIMIT)) {
            segments = JpegSegmentReader.readSegments(
                    new StreamReader(in), List.of(JpegSegmentType.APP1, JpegSegmentType.APPD));
        } catch (JpegProcessingException | java.io.EOFException | RuntimeException notJpeg) {
            return;
        }
        byte[] iptcBlock = iptcResource(segments.getSegments(JpegSegmentType.APPD));
        if (iptcBlock != null) {
            Metadata metadata = new Metadata();
            try {
                new IptcReader().extract(new SequentialByteArrayReader(iptcBlock), metadata, iptcBlock.length);
            } catch (RuntimeException unreadable) {
                // a broken IPTC block reads as far as it goes
            }
            IptcDirectory iptc = metadata.getFirstDirectoryOfType(IptcDirectory.class);
            if (iptc != null) {
                readIptc(iptc, fields);
            }
        }
        byte[] app1 = exifSegment(segments.getSegments(JpegSegmentType.APP1));
        if (app1 == null) {
            return;
        }
        Metadata exif = exifMetadata(app1);
        readExif(
                exif.getFirstDirectoryOfType(ExifIFD0Directory.class),
                exif.getFirstDirectoryOfType(ExifSubIFDDirectory.class),
                app1[EXIF_PREAMBLE.length] == 'M',
                fields);
    }

    /**
     * The EXIF tags whose values are documents of their own, never parsed here: XMP, IPTC, Photoshop, ICC, maker
     * notes.
     */
    private static final Set<Integer> EMBEDDED_DOCUMENTS = Set.of(0x02BC, 0x83BB, 0x8649, 0x8773, 0x927C);

    /** EXIF's TIFF handler without the embedded documents, so a camera tag never starts another parser. */
    private static final class PlainExifHandler extends ExifTiffHandler {
        PlainExifHandler(Metadata metadata) {
            super(metadata, null, EXIF_PREAMBLE.length);
        }

        @Override
        public boolean customProcessTag(
                int tagOffset,
                Set<Integer> processedIfdOffsets,
                int tiffHeaderOffset,
                RandomAccessReader reader,
                int tagId,
                int byteCount)
                throws IOException {
            if (EMBEDDED_DOCUMENTS.contains(tagId)) {
                return true;
            }
            return super.customProcessTag(tagOffset, processedIfdOffsets, tiffHeaderOffset, reader, tagId, byteCount);
        }
    }

    /** The directories of one EXIF APP1 segment, its embedded documents left unparsed. */
    static Metadata exifMetadata(byte[] app1) {
        Metadata exif = new Metadata();
        try {
            new TiffReader().processTiff(new ByteArrayReader(app1), new PlainExifHandler(exif), EXIF_PREAMBLE.length);
        } catch (TiffProcessingException | IOException | RuntimeException unreadable) {
            // what was read before the damage counts, as with PHP
        }
        return exif;
    }

    /** The first APP1 segment that holds EXIF ({@code Exif\0\0} and a TIFF header), or null. */
    private static byte[] exifSegment(Iterable<byte[]> app1) {
        for (byte[] segment : app1) {
            if (segment.length > EXIF_PREAMBLE.length + 8 && startsWith(segment, EXIF_PREAMBLE)) {
                return segment;
            }
        }
        return null;
    }

    /**
     * The IPTC data of the first APP13 segment: its Photoshop image resource 0x0404, found by walking the resource
     * blocks ({@code 8BIM}, id, padded Pascal name, size, padded data) without reading any other resource; null when
     * there is none.
     */
    static byte[] iptcResource(Iterable<byte[]> app13) {
        for (byte[] segment : app13) {
            if (!startsWith(segment, PHOTOSHOP_PREAMBLE)) {
                continue;
            }
            int at = PHOTOSHOP_PREAMBLE.length;
            int blocks = 0;
            while (at + 12 <= segment.length && blocks++ < MAX_RESOURCES) {
                if (segment[at] != '8' || segment[at + 1] != 'B' || segment[at + 2] != 'I' || segment[at + 3] != 'M') {
                    return null;
                }
                int id = ((segment[at + 4] & 0xFF) << 8) | (segment[at + 5] & 0xFF);
                int nameLength = segment[at + 6] & 0xFF;
                int sizeAt = at + 6 + ((nameLength + 2) & ~1);
                if (sizeAt + 4 > segment.length) {
                    return null;
                }
                long size = ((segment[sizeAt] & 0xFFL) << 24)
                        | ((segment[sizeAt + 1] & 0xFFL) << 16)
                        | ((segment[sizeAt + 2] & 0xFFL) << 8)
                        | (segment[sizeAt + 3] & 0xFFL);
                int dataAt = sizeAt + 4;
                if (size > segment.length - dataAt) {
                    return null;
                }
                if (id == 0x0404) {
                    return Arrays.copyOfRange(segment, dataAt, dataAt + (int) size);
                }
                at = dataAt + (int) ((size + 1) & ~1L);
            }
            return null;
        }
        return null;
    }

    static void readIptc(Directory iptc, Fields fields) {
        byte[] headline = first(iptc, IptcDirectory.TAG_HEADLINE);
        byte[] objectName = first(iptc, IptcDirectory.TAG_OBJECT_NAME);
        if (nonEmpty(headline)) {
            fields.title = trim(headline);
        } else if (nonEmpty(objectName)) {
            fields.title = trim(objectName);
        }
        byte[] caption = first(iptc, IptcDirectory.TAG_CAPTION);
        if (nonEmpty(caption)) {
            fields.caption = trim(caption);
            if (fields.title.length == 0 && fields.caption.length < TITLE_BYTES) {
                fields.title = fields.caption;
            }
        }
        byte[] credit = first(iptc, IptcDirectory.TAG_CREDIT);
        byte[] byLine = first(iptc, IptcDirectory.TAG_BY_LINE);
        if (nonEmpty(credit)) {
            fields.credit = trim(credit);
        } else if (nonEmpty(byLine)) {
            fields.credit = trim(byLine);
        }
        byte[] date = first(iptc, IptcDirectory.TAG_DATE_CREATED);
        byte[] time = first(iptc, IptcDirectory.TAG_TIME_CREATED);
        if (nonEmpty(date) && nonEmpty(time)) {
            OptionalLong created = ExifNumbers.iptcDateTime(latin1(trim(date)), latin1(trim(time)));
            fields.createdTimestamp = created.isPresent() ? (Object) created.getAsLong() : Boolean.FALSE;
        }
        byte[] copyright = first(iptc, IptcDirectory.TAG_COPYRIGHT_NOTICE);
        if (nonEmpty(copyright)) {
            fields.copyright = trim(copyright);
        }
        List<byte[]> keywords = all(iptc, IptcDirectory.TAG_KEYWORDS);
        if (!keywords.isEmpty() && nonEmpty(keywords.get(0))) {
            fields.keywords = keywords;
        }
    }

    static void readExif(Directory ifd0, Directory sub, boolean bigEndian, Fields fields) {
        if (ifd0 == null && sub == null) {
            return;
        }
        byte[] description = trim(text(ifd0, ExifDirectoryBase.TAG_IMAGE_DESCRIPTION, bigEndian));
        byte[] userComment = trim(userComment(sub, bigEndian));
        byte[] comments = trim(text(ifd0, ExifDirectoryBase.TAG_WIN_COMMENT, bigEndian));
        if (nonEmpty(description)) {
            if (fields.title.length == 0 && description.length < TITLE_BYTES) {
                fields.title = description;
            }
            if (fields.caption.length == 0 && nonEmpty(userComment)) {
                if (Arrays.equals(fields.title, description)) {
                    fields.caption = userComment;
                } else if (Arrays.equals(description, userComment)) {
                    fields.caption = description;
                } else {
                    fields.caption = trim(concat(description, new byte[] {' '}, userComment));
                }
            }
            if (fields.caption.length == 0) {
                fields.caption = description;
            }
        } else if (fields.caption.length == 0 && nonEmpty(userComment)) {
            fields.caption = userComment;
            if (fields.title.length == 0 && userComment.length < TITLE_BYTES) {
                fields.title = userComment;
            }
        } else if (fields.caption.length == 0 && nonEmpty(comments)) {
            fields.caption = comments;
        }
        if (fields.credit.length == 0) {
            byte[] artist = text(ifd0, ExifDirectoryBase.TAG_ARTIST, bigEndian);
            byte[] author = text(ifd0, ExifDirectoryBase.TAG_WIN_AUTHOR, bigEndian);
            if (nonEmpty(artist)) {
                fields.credit = trim(artist);
            } else if (nonEmpty(author)) {
                fields.credit = trim(author);
            }
        }
        if (fields.copyright.length == 0) {
            byte[] copyright = text(ifd0, ExifDirectoryBase.TAG_COPYRIGHT, bigEndian);
            if (nonEmpty(copyright)) {
                fields.copyright = trim(copyright);
            }
        }
        Object fNumber = value(sub, ExifDirectoryBase.TAG_FNUMBER);
        if (fNumber instanceof Rational rational) {
            Object number = ExifNumbers.frac2dec(fraction(rational));
            fields.aperture = Dimensions.round(((Number) number).doubleValue(), 2);
        }
        byte[] model = text(ifd0, ExifDirectoryBase.TAG_MODEL, bigEndian);
        if (nonEmpty(model)) {
            fields.camera = trim(model);
        }
        if (fields.createdIsEmpty()) {
            byte[] digitized = text(sub, ExifDirectoryBase.TAG_DATETIME_DIGITIZED, bigEndian);
            if (nonEmpty(digitized)) {
                OptionalLong created = ExifNumbers.date2ts(latin1(digitized));
                fields.createdTimestamp = created.isPresent() ? (Object) created.getAsLong() : Boolean.FALSE;
            }
        }
        Object focalLength = value(sub, ExifDirectoryBase.TAG_FOCAL_LENGTH);
        if (focalLength != null) {
            fields.focalLength = fractionValue(focalLength);
        }
        Object iso = value(sub, ExifDirectoryBase.TAG_ISO_EQUIVALENT);
        if (iso instanceof int[] values && values.length > 0) {
            iso = values[0];
        }
        if (iso instanceof Number n && n.longValue() != 0) {
            fields.iso = ascii(Long.toString(n.longValue()));
        }
        Object exposure = value(sub, ExifDirectoryBase.TAG_EXPOSURE_TIME);
        if (exposure != null) {
            fields.shutterSpeed = fractionValue(exposure);
        }
        Object orientation = value(ifd0, ExifDirectoryBase.TAG_ORIENTATION);
        if (orientation instanceof Number n && n.longValue() != 0) {
            fields.orientation = n.longValue();
        }
    }

    /** A fraction field as a number: a single fraction is divided, a list of them gives 0. */
    private static Object fractionValue(Object value) {
        if (value instanceof Rational rational) {
            return ExifNumbers.frac2dec(fraction(rational));
        }
        if (value instanceof Number n) {
            return n instanceof Double || n instanceof Float ? (Object) n.doubleValue() : (Object) n.longValue();
        }
        return 0L;
    }

    private static String fraction(Rational rational) {
        return rational.getNumerator() + "/" + rational.getDenominator();
    }

    private static Object value(Directory directory, int tag) {
        return directory == null ? null : directory.getObject(tag);
    }

    /**
     * A text field as PHP's EXIF reader gives it: the stored bytes of an ASCII field; the Windows XP fields (UCS-2 in
     * the file's byte order) converted to ISO-8859-15, as PHP's default {@code exif.encode_unicode} does.
     */
    private static byte[] text(Directory directory, int tag, boolean bigEndian) {
        Object value = value(directory, tag);
        if (value instanceof StringValue string) {
            return string.getBytes();
        }
        if (value instanceof String string) {
            return string.getBytes(StandardCharsets.UTF_8);
        }
        if (value instanceof short[] units
                && (tag == ExifDirectoryBase.TAG_WIN_COMMENT || tag == ExifDirectoryBase.TAG_WIN_AUTHOR)) {
            byte[] bytes = new byte[units.length];
            for (int i = 0; i < units.length; i++) {
                bytes[i] = (byte) units[i];
            }
            return ucs2(bytes, 0, bytes.length, bigEndian, true);
        }
        if (value instanceof byte[] bytes
                && (tag == ExifDirectoryBase.TAG_WIN_COMMENT || tag == ExifDirectoryBase.TAG_WIN_AUTHOR)) {
            return ucs2(bytes, 0, bytes.length, bigEndian, true);
        }
        if (value instanceof Number n) {
            return ascii(n.toString());
        }
        return EMPTY;
    }

    /**
     * The user comment without its eight-byte character code: {@code UNICODE} text (UCS-2 in the file's byte order)
     * converted to ISO-8859-15, any other code's bytes as stored.
     */
    private static byte[] userComment(Directory sub, boolean bigEndian) {
        Object value = value(sub, ExifDirectoryBase.TAG_USER_COMMENT);
        if (!(value instanceof byte[] bytes)) {
            return value instanceof StringValue string ? string.getBytes() : EMPTY;
        }
        if (bytes.length >= 8) {
            if (startsWith(bytes, ascii("UNICODE\0"))) {
                return ucs2(bytes, 8, bytes.length, bigEndian, false);
            }
            if (startsWith(bytes, ascii("JIS\0\0\0\0\0"))) {
                String text = new String(bytes, 8, bytes.length - 8, ISO_2022_JP);
                return text.getBytes(ISO_8859_15);
            }
            if (startsWith(bytes, ascii("ASCII\0\0\0")) || startsWith(bytes, new byte[8])) {
                return upToNul(bytes, 8);
            }
        }
        // an unknown character code: the whole value as text
        return upToNul(bytes, 0);
    }

    private static byte[] upToNul(byte[] bytes, int from) {
        int end = from;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return Arrays.copyOfRange(bytes, from, end);
    }

    /**
     * UCS-2 text as ISO-8859-15 bytes ({@code ?} for characters it lacks), up to its first NUL character when
     * {@code stopAtNul}, else NUL characters kept.
     */
    private static byte[] ucs2(byte[] bytes, int from, int to, boolean bigEndian, boolean stopAtNul) {
        StringBuilder text = new StringBuilder((to - from) / 2);
        for (int i = from; i + 1 < to; i += 2) {
            int hi = bigEndian ? bytes[i] & 0xFF : bytes[i + 1] & 0xFF;
            int lo = bigEndian ? bytes[i + 1] & 0xFF : bytes[i] & 0xFF;
            char c = (char) (hi << 8 | lo);
            if (c == 0 && stopAtNul) {
                break;
            }
            text.append(c);
        }
        return text.toString().getBytes(ISO_8859_15);
    }

    /** The first value of an IPTC dataset as its stored bytes; empty when absent. */
    private static byte[] first(Directory iptc, int tag) {
        Object value = iptc.getObject(tag);
        if (value instanceof StringValue[] values) {
            return values.length == 0 ? EMPTY : values[0].getBytes();
        }
        if (value instanceof String[] values) {
            return values.length == 0 ? EMPTY : values[0].getBytes(StandardCharsets.UTF_8);
        }
        return single(value);
    }

    /** Every value of an IPTC dataset, in order, as stored; at most {@link #MAX_KEYWORDS}. */
    private static List<byte[]> all(Directory iptc, int tag) {
        Object value = iptc.getObject(tag);
        List<byte[]> values = new ArrayList<>();
        if (value instanceof StringValue[] strings) {
            for (int i = 0; i < strings.length && i < MAX_KEYWORDS; i++) {
                values.add(strings[i].getBytes());
            }
        } else if (value instanceof String[] strings) {
            for (int i = 0; i < strings.length && i < MAX_KEYWORDS; i++) {
                values.add(strings[i].getBytes(StandardCharsets.UTF_8));
            }
        } else if (value != null) {
            values.add(single(value));
        }
        return List.copyOf(values);
    }

    private static byte[] single(Object value) {
        if (value instanceof StringValue string) {
            return string.getBytes();
        }
        if (value instanceof String string) {
            return string.getBytes(StandardCharsets.UTF_8);
        }
        if (value instanceof Number n) {
            return ascii(n.toString());
        }
        return EMPTY;
    }

    // ---- XMP -------------------------------------------------------------------------------------------------------

    /** The bytes from the first {@code <x:xmpmeta} to the end of the first {@code </x:xmpmeta>}; empty when none. */
    static byte[] xmpPacket(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(XMP_LIMIT);
        }
        int start = indexOf(head, XMP_START);
        int end = indexOf(head, XMP_END);
        if (start < 0 || end < start) {
            return EMPTY;
        }
        return Arrays.copyOfRange(head, start, end + XMP_END.length);
    }

    /**
     * The alternative text of an XMP packet: under {@code x:xmpmeta / rdf:RDF / rdf:Description /
     * Iptc4xmpCore:AltTextAccessibility} (elements, the first match), the {@code rdf:Alt / rdf:li} item whose
     * {@code xml:lang} is the locale, else the locale's first two letters, else {@code x-default}. Empty when the
     * packet is missing, not well-formed XML (a document type declaration included) or has no such item.
     */
    static String xmpAlt(byte[] packet, String locale) {
        if (packet.length == 0) {
            return "";
        }
        Document document;
        try {
            document = parser().parse(new ByteArrayInputStream(packet));
        } catch (SAXException | IOException | ParserConfigurationException | RuntimeException invalid) {
            return "";
        }
        Element root = document.getDocumentElement();
        if (!is(root, XMP_META, "xmpmeta")) {
            return "";
        }
        Element alt = null;
        for (Element rdf : children(root, RDF, "RDF")) {
            for (Element description : children(rdf, RDF, "Description")) {
                List<Element> found = children(description, IPTC_CORE, "AltTextAccessibility");
                if (!found.isEmpty()) {
                    alt = found.get(0);
                    break;
                }
            }
            if (alt != null) {
                break;
            }
        }
        if (alt == null) {
            return "";
        }
        List<Element> items = new ArrayList<>();
        for (Element bag : children(alt, RDF, "Alt")) {
            items.addAll(children(bag, RDF, "li"));
        }
        String language = locale == null ? "" : locale;
        String[] wanted = {language, language.substring(0, Math.min(2, language.length())), "x-default"};
        for (String lang : wanted) {
            if (lang.isEmpty()) {
                continue;
            }
            for (Element item : items) {
                if (item.hasAttributeNS(XML, "lang")
                        && item.getAttributeNS(XML, "lang").equals(lang)) {
                    return textOf(item);
                }
            }
        }
        return "";
    }

    /** The text of a node and its descendants, in document order, collected without recursion. */
    static String textOf(Node root) {
        StringBuilder text = new StringBuilder();
        Node node = root.getFirstChild();
        while (node != null) {
            short type = node.getNodeType();
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
            Node next = type == Node.ELEMENT_NODE ? node.getFirstChild() : null;
            while (next == null && node != root) {
                next = node.getNextSibling();
                if (next == null) {
                    node = node.getParentNode();
                }
            }
            node = node == root ? null : next;
        }
        return text.toString();
    }

    private static DocumentBuilder parser() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        // libxml2, which PHP's DOM uses, refuses documents nested deeper than this
        factory.setAttribute(MAX_ELEMENT_DEPTH, String.valueOf(MAX_XML_DEPTH));
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) {}

            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        });
        return builder;
    }

    private static List<Element> children(Element parent, String namespace, String localName) {
        List<Element> found = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && is(element, namespace, localName)) {
                found.add(element);
            }
        }
        return found;
    }

    private static boolean is(Element element, String namespace, String localName) {
        return element != null
                && namespace.equals(element.getNamespaceURI())
                && localName.equals(element.getLocalName());
    }

    // ---- PHP byte strings ------------------------------------------------------------------------------------------

    /** PHP's truthiness of a string: neither empty nor {@code "0"}. */
    static boolean nonEmpty(byte[] text) {
        return text.length > 0 && !(text.length == 1 && text[0] == '0');
    }

    /** PHP's {@code trim}: space, tab, line feed, carriage return, NUL and vertical tab off both ends. */
    static byte[] trim(byte[] text) {
        int start = 0;
        int end = text.length;
        while (start < end && isTrimmed(text[start])) {
            start++;
        }
        while (end > start && isTrimmed(text[end - 1])) {
            end--;
        }
        return start == 0 && end == text.length ? text : Arrays.copyOfRange(text, start, end);
    }

    /** PHP's {@code trim} of a string, for texts already decoded. */
    static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isTrimmed(text.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmed(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isTrimmed(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0 || c == 0x0B;
    }

    /** The bytes as UTF-8 when they are valid UTF-8, else each byte as one ISO-8859-1 character. */
    static String repaired(byte[] text) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(text));
            return decoded.toString();
        } catch (CharacterCodingException notUtf8) {
            return latin1(text);
        }
    }

    private static String latin1(byte[] text) {
        return new String(text, StandardCharsets.ISO_8859_1);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] bytes, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= bytes.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] joined = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, joined, at, part.length);
            at += part.length;
        }
        return joined;
    }
}

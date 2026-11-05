package org.quarkpress.media.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.ParseContext;

/**
 * The content type of an uploaded file as Apache Tika reads it from the bytes alone, renamed where Tika's name differs
 * from the one WordPress's acceptance rules compare (the answer of libmagic, which WordPress asks).
 *
 * <p>Tika never sees the file name: given one, it would let the name decide, and the point of the check is to catch
 * a name that lies. Only a bounded prefix of the file is read.
 *
 * <p>Renamed answers:
 *
 * <ul>
 *   <li>{@code application/x-tika-ooxml} (an Office Open XML container Tika cannot name without its parser module)
 *       becomes {@code application/zip};
 *   <li>{@code application/x-tika-msoffice} (an OLE2 container) becomes {@code application/octet-stream};
 *   <li>{@code application/x-tika-ooxml-protected} (an encrypted Office Open XML file) becomes
 *       {@code application/encrypted};
 *   <li>{@code application/x-matroska} becomes {@code video/webm} or {@code video/x-matroska} by the EBML header's
 *       DocType ({@code webm} or {@code matroska}): tika-core 4.2.0 has no content signature for WebM at all;
 *   <li>{@code application/ogg} becomes {@code video/ogg} when the first packet is a Theora identification header:
 *       tika-core 4.2.0's Theora signature looks one byte too far into the page, so it never matches.
 * </ul>
 *
 * Every other answer is Tika's, without parameters ({@code application/x-rar-compressed; version=4} becomes
 * {@code application/x-rar-compressed}). A refinement that cannot read its header (truncated or malformed) keeps
 * Tika's answer.
 */
public final class TikaTypes {

    /** How many leading bytes are read: at least Tika's longest content signature. */
    static final int PREFIX_BYTES =
            Math.max(64 * 1024, MimeTypes.getDefaultMimeTypes().getMinLength());

    private static final Detector DETECTOR = new DefaultDetector();

    private static final String MATROSKA = "application/x-matroska";
    private static final String OGG = "application/ogg";

    /** The EBML header's element id. */
    private static final long EBML_ID = 0x1A45DFA3L;

    /** The DocType element's id inside the EBML header. */
    private static final long DOC_TYPE_ID = 0x4282L;

    private static final byte[] THEORA = {(byte) 0x80, 't', 'h', 'e', 'o', 'r', 'a'};

    private TikaTypes() {}

    /**
     * The content type of {@code file}, read from its content only.
     *
     * @param file the uploaded file
     * @return the type, such as {@code image/jpeg} or {@code text/plain}, never with parameters
     * @throws IOException when the file cannot be read
     */
    public static String detect(Path file) throws IOException {
        return detect(prefix(file));
    }

    /** The first {@link #PREFIX_BYTES} bytes of {@code file} (all of a shorter file). */
    static byte[] prefix(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(PREFIX_BYTES);
        }
    }

    /** The content type of a file that starts with {@code prefix}. */
    static String detect(byte[] prefix) throws IOException {
        MediaType type;
        try (TikaInputStream in = TikaInputStream.get(prefix)) {
            type = DETECTOR.detect(in, new Metadata(), new ParseContext());
        }
        String base = type.getBaseType().toString();
        return switch (base) {
            case "application/x-tika-ooxml" -> "application/zip";
            case "application/x-tika-msoffice" -> "application/octet-stream";
            case "application/x-tika-ooxml-protected" -> "application/encrypted";
            case MATROSKA -> matroskaDocType(prefix).orElse(base);
            case OGG -> startsWithTheora(prefix) ? "video/ogg" : base;
            default -> base;
        };
    }

    /**
     * The type the DocType of an EBML header names: {@code video/webm} for {@code webm}, {@code video/x-matroska} for
     * {@code matroska}; empty for another DocType, none, or a header that is malformed or cut off.
     */
    static Optional<String> matroskaDocType(byte[] bytes) {
        int[] at = {0};
        long id = readVint(bytes, at, bytes.length, true);
        if (id != EBML_ID) {
            return Optional.empty();
        }
        long size = readVint(bytes, at, bytes.length, false);
        if (size < 0) {
            return Optional.empty();
        }
        long declaredEnd = at[0] + size;
        if (declaredEnd > bytes.length) {
            return Optional.empty();
        }
        int end = (int) declaredEnd;
        while (at[0] < end) {
            long childId = readVint(bytes, at, end, true);
            long childSize = childId < 0 ? -1 : readVint(bytes, at, end, false);
            if (childSize < 0 || childSize > end - at[0]) {
                return Optional.empty();
            }
            int start = at[0];
            int length = (int) childSize;
            if (childId == DOC_TYPE_ID) {
                while (length > 0 && bytes[start + length - 1] == 0) {
                    length--;
                }
                if (isAscii(bytes, start, length, "webm")) {
                    return Optional.of("video/webm");
                }
                if (isAscii(bytes, start, length, "matroska")) {
                    return Optional.of("video/x-matroska");
                }
                return Optional.empty();
            }
            at[0] = start + length;
        }
        return Optional.empty();
    }

    /**
     * One EBML variable-length integer at {@code at[0]}, which it advances: an element id keeps its length marker
     * ({@code id} true), a size does not. Answers -1 for a malformed value, one that runs past {@code end}, an id
     * longer than four bytes, or a size of all ones (unknown).
     */
    private static long readVint(byte[] bytes, int[] at, int end, boolean id) {
        int position = at[0];
        if (position >= end) {
            return -1;
        }
        int first = bytes[position] & 0xFF;
        if (first == 0) {
            return -1;
        }
        int length = Integer.numberOfLeadingZeros(first) - 23;
        if ((id && length > 4) || position + length > end) {
            return -1;
        }
        long value = id ? first : first & (0xFF >> length);
        boolean allOnes = value == (0xFF >> length);
        for (int i = 1; i < length; i++) {
            int next = bytes[position + i] & 0xFF;
            allOnes &= next == 0xFF;
            value = (value << 8) | next;
        }
        if (!id && allOnes) {
            return -1;
        }
        at[0] = position + length;
        return value;
    }

    private static boolean isAscii(byte[] bytes, int start, int length, String text) {
        if (length != text.length()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (bytes[start + i] != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the first packet of the first Ogg page is a Theora identification header. */
    static boolean startsWithTheora(byte[] bytes) {
        if (bytes.length < 27
                || bytes[0] != 'O'
                || bytes[1] != 'g'
                || bytes[2] != 'g'
                || bytes[3] != 'S'
                || bytes[4] != 0) {
            return false;
        }
        int segments = bytes[26] & 0xFF;
        int packet = 27 + segments;
        if (segments == 0 || packet + THEORA.length > bytes.length) {
            return false;
        }
        for (int i = 0; i < THEORA.length; i++) {
            if (bytes[packet + i] != THEORA[i]) {
                return false;
            }
        }
        return true;
    }
}

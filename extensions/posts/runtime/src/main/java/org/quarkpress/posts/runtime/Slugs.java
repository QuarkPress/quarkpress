package org.quarkpress.posts.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import org.quarkpress.lib.wpkses.Kses;
import org.quarkpress.security.runtime.RemoveAccents;
import org.quarkpress.security.runtime.UserNames;

/**
 * WordPress's slug functions: {@code sanitize_title} (the save context removes accents with the {@code en_US} rules
 * and turns a few characters into hyphens or drops them; the query and old-save contexts do neither), the URL
 * encoding of non-ASCII characters with a byte limit that never splits a character, the truncation of a slug that
 * keeps whole characters, and PHP's {@code urlencode}.
 */
public final class Slugs {

    /** The longest slug WordPress stores, in bytes. */
    public static final int MAX_BYTES = 200;

    /** Characters the save context turns into a hyphen: no-break space, no-break hyphen, en and em dash. */
    private static final int[] HYPHENATED = {0x00A0, 0x2011, 0x2013, 0x2014};

    /** Entities the save context turns into a hyphen. */
    private static final String[] HYPHENATED_ENTITIES = {
        "&nbsp;", "&#8209;", "&#160;", "&ndash;", "&#8211;", "&mdash;", "&#8212;"
    };

    /**
     * Characters the save context drops: soft hyphen, inverted marks, angle and curly quotes, bullet, copyright,
     * registered, degree, ellipsis, trade mark, acute accents, combining grave, macron and caron, zero-width and
     * direction characters, byte order mark, object replacement character.
     */
    private static final int[] DROPPED = {
        0x00AD, 0x00A1, 0x00BF, 0x00AB, 0x00BB, 0x2039, 0x203A, 0x2018, 0x2019, 0x201C, 0x201D, 0x201A, 0x201B,
        0x201E, 0x201F, 0x2022, 0x00A9, 0x00AE, 0x00B0, 0x2026, 0x2122, 0x00B4, 0x02CA, 0x0301, 0x0341, 0x0300,
        0x0304, 0x030C, 0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0xFEFF,
        0xFFFC
    };

    /** Spaces with a width the save context turns into a hyphen. */
    private static final int[] WIDE_SPACES = {
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F
    };

    /** The multiplication sign, which the save context writes as x. */
    private static final int TIMES = 0x00D7;

    private static final int SIGMA = 0x03A3;

    /** Word-internal punctuation that is case-ignorable: apostrophe, full stop, colon and similar marks. */
    private static final int[] WORD_INTERNAL = {
        0x0027, 0x002E, 0x003A, 0x00B7, 0x0387, 0x055F, 0x05F4, 0x2018, 0x2019, 0x2024, 0x2027, 0xFE13, 0xFE52, 0xFE55,
        0xFF07, 0xFF0E, 0xFF1A
    };

    private Slugs() {}

    /** {@code sanitize_title} in its save context. */
    public static String sanitizeTitle(String title) {
        return withDashes(RemoveAccents.enUs(Objects.requireNonNull(title, "title")), true);
    }

    /** {@code sanitize_title} in its save context with a fallback for an empty result. */
    public static String sanitizeTitle(String title, String fallback) {
        String slug = sanitizeTitle(title);
        return slug.isEmpty() ? fallback : slug;
    }

    /**
     * {@code sanitize_title} in its query context (also its old-save context): accents and save-only characters
     * kept.
     */
    public static String sanitizeTitleForQuery(String title) {
        return withDashes(Objects.requireNonNull(title, "title"), false);
    }

    /** {@code sanitize_title_with_dashes}; {@code save} applies the save-only replacements. */
    public static String withDashes(String title, boolean save) {
        boolean valid = !hasLoneSurrogate(title);
        String text = keepOctets(Kses.stripTags(title));
        if (valid) {
            text = utf8UriEncode(lowerCase(text), MAX_BYTES);
        }
        text = asciiLower(text);
        if (save) {
            for (int c : HYPHENATED) {
                text = text.replace(encoded(c), "-");
            }
            for (String entity : HYPHENATED_ENTITIES) {
                text = text.replace(entity, "-");
            }
            text = text.replace("/", "-");
            for (int c : DROPPED) {
                text = text.replace(encoded(c), "");
            }
            for (int c : WIDE_SPACES) {
                text = text.replace(encoded(c), "-");
            }
            text = text.replace(encoded(TIMES), "x");
        }
        text = UserNames.removeEntities(text).replace(".", "-");
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingHyphen = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean kept = c == '%' || c == '_' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (c == ' ' || c == '-') {
                pendingHyphen = true;
            } else if (kept) {
                if (pendingHyphen && out.length() > 0) {
                    out.append('-');
                }
                pendingHyphen = false;
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * {@code utf8_uri_encode}: non-ASCII characters of the text's UTF-8 form as lower-case percent octets, stopping
     * before the character that would make the result longer than {@code maxBytes} (0: no limit).
     */
    public static String utf8UriEncode(String text, int maxBytes) {
        return encodeBytes(text.getBytes(StandardCharsets.UTF_8), maxBytes, false);
    }

    /**
     * {@code _truncate_post_slug}: a slug longer than {@code maxBytes} is cut to that many bytes, keeping whole
     * characters when it holds percent octets (they are decoded and encoded again); trailing hyphens are then
     * removed, also from a slug that was not cut.
     */
    public static String truncate(String slug, int maxBytes) {
        byte[] bytes = slug.getBytes(StandardCharsets.UTF_8);
        String result = slug;
        if (bytes.length > maxBytes) {
            byte[] decoded = urlDecode(bytes);
            result = Arrays.equals(decoded, bytes)
                    ? new String(bytes, 0, maxBytes, StandardCharsets.UTF_8)
                    : encodeBytes(decoded, maxBytes, true);
        }
        int end = result.length();
        while (end > 0 && result.charAt(end - 1) == '-') {
            end--;
        }
        return result.substring(0, end);
    }

    /**
     * PHP's {@code urlencode}: letters, digits and {@code - _ .} kept, space as {@code +}, other bytes as
     * {@code %XX}.
     */
    public static String phpUrlencode(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            if ((v >= 'a' && v <= 'z')
                    || (v >= 'A' && v <= 'Z')
                    || (v >= '0' && v <= '9')
                    || v == '-'
                    || v == '_'
                    || v == '.') {
                out.append((char) v);
            } else if (v == ' ') {
                out.append('+');
            } else {
                out.append('%').append(hexUpper(v));
            }
        }
        return out.toString();
    }

    /** The length of the text's UTF-8 form. */
    public static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Lower-cases as PHP's {@code mb_strtolower} does. Everything is lower-cased by the JDK except capital sigma,
     * which becomes final sigma only under the Unicode Final_Sigma condition: a cased letter before it with only
     * case-ignorable characters in between, and no cased letter after it with only case-ignorable characters in
     * between. Approximations: "cased" is a lower-case, upper-case or title-case letter as the JDK defines them, and
     * "case-ignorable" is a character of type Mn, Me, Cf, Lm or Sk plus the apostrophe, full stop, colon, middle dot
     * and the quotation and punctuation marks Unicode lists as word-internal.
     */
    private static String lowerCase(String text) {
        if (text.indexOf(SIGMA) < 0) {
            return text.toLowerCase(java.util.Locale.ROOT);
        }
        int[] points = text.codePoints().toArray();
        StringBuilder out = new StringBuilder(text.length());
        int start = 0;
        for (int i = 0; i < points.length; i++) {
            if (points[i] == SIGMA) {
                out.append(new String(points, start, i - start).toLowerCase(java.util.Locale.ROOT));
                out.appendCodePoint(isFinalSigma(points, i) ? 0x3C2 : 0x3C3);
                start = i + 1;
            }
        }
        out.append(new String(points, start, points.length - start).toLowerCase(java.util.Locale.ROOT));
        return out.toString();
    }

    private static boolean isFinalSigma(int[] points, int at) {
        int before = at - 1;
        while (before >= 0 && isCaseIgnorable(points[before])) {
            before--;
        }
        if (before < 0 || !isCased(points[before])) {
            return false;
        }
        int after = at + 1;
        while (after < points.length && isCaseIgnorable(points[after])) {
            after++;
        }
        return after >= points.length || !isCased(points[after]);
    }

    private static boolean isCased(int c) {
        return Character.isLowerCase(c) || Character.isUpperCase(c) || Character.isTitleCase(c);
    }

    private static boolean isCaseIgnorable(int c) {
        switch (Character.getType(c)) {
            case Character.NON_SPACING_MARK,
                    Character.ENCLOSING_MARK,
                    Character.FORMAT,
                    Character.MODIFIER_LETTER,
                    Character.MODIFIER_SYMBOL -> {
                return true;
            }
            default -> {
                for (int w : WORD_INTERNAL) {
                    if (w == c) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    private static String encoded(int codePoint) {
        return utf8UriEncode(new String(Character.toChars(codePoint)), 0);
    }

    private static String encodeBytes(byte[] bytes, int maxBytes, boolean encodeAscii) {
        StringBuilder out = new StringBuilder();
        int length = 0;
        int[] pending = new int[4];
        int count = 0;
        int octets = 1;
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v < 128) {
                String ascii = encodeAscii ? rawUrlencode(v) : String.valueOf((char) v);
                if (maxBytes > 0 && length + ascii.length() > maxBytes) {
                    break;
                }
                out.append(ascii);
                length += ascii.length();
            } else {
                if (count == 0) {
                    octets = v < 224 ? 2 : v < 240 ? 3 : 4;
                }
                if (count < pending.length) {
                    pending[count] = v;
                }
                count++;
                if (maxBytes > 0 && length + octets * 3 > maxBytes) {
                    break;
                }
                if (count == octets) {
                    for (int i = 0; i < octets; i++) {
                        out.append('%').append(Integer.toHexString(pending[i]));
                    }
                    length += octets * 3;
                    count = 0;
                    octets = 1;
                }
            }
        }
        return out.toString();
    }

    private static String rawUrlencode(int v) {
        if ((v >= 'a' && v <= 'z')
                || (v >= 'A' && v <= 'Z')
                || (v >= '0' && v <= '9')
                || v == '-'
                || v == '_'
                || v == '.'
                || v == '~') {
            return String.valueOf((char) v);
        }
        return "%" + hexUpper(v);
    }

    private static String hexUpper(int v) {
        String hex = Integer.toHexString(v).toUpperCase(java.util.Locale.ROOT);
        return hex.length() == 1 ? "0" + hex : hex;
    }

    /** PHP's {@code urldecode} on bytes: {@code +} as a space, {@code %XX} as that byte, anything else as it is. */
    private static byte[] urlDecode(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            if (v == '+') {
                out.write(' ');
            } else if (v == '%' && i + 2 < bytes.length && isHex(bytes[i + 1]) && isHex(bytes[i + 2])) {
                out.write(Integer.parseInt(new String(bytes, i + 1, 2, StandardCharsets.US_ASCII), 16));
                i += 2;
            } else {
                out.write(v);
            }
        }
        return out.toByteArray();
    }

    /**
     * WordPress keeps percent octets and drops every other {@code %}: an octet is first written as
     * {@code ---HH---}, the remaining {@code %} are removed, and every {@code ---HH---} becomes {@code %HH} again
     * (so text that already held {@code ---HH---} turns into an octet too).
     */
    private static String keepOctets(String text) {
        StringBuilder marked = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < text.length() && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2))) {
                marked.append("---").append(text, i + 1, i + 3).append("---");
                i += 2;
            } else if (c != '%') {
                marked.append(c);
            }
        }
        StringBuilder out = new StringBuilder(marked.length());
        int i = 0;
        while (i < marked.length()) {
            if (i + 8 <= marked.length()
                    && marked.charAt(i) == '-'
                    && marked.charAt(i + 1) == '-'
                    && marked.charAt(i + 2) == '-'
                    && isHex(marked.charAt(i + 3))
                    && isHex(marked.charAt(i + 4))
                    && marked.charAt(i + 5) == '-'
                    && marked.charAt(i + 6) == '-'
                    && marked.charAt(i + 7) == '-') {
                out.append('%').append(marked, i + 3, i + 5);
                i += 8;
            } else {
                out.append(marked.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static String asciiLower(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return out.toString();
    }

    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isHex(byte b) {
        return isHex((char) (b & 0xFF));
    }
}

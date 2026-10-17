package org.quarkpress.lib.wpkses;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * HTML5 character-reference decoding of attribute values, as WordPress's HTML API does it: the
 * full HTML5 named-reference table (resource {@code html5-named-references.txt}), numeric references with or
 * without ';', the Windows-1252 remapping of 0x80-0x9F, and the attribute-value rule for legacy names.
 */
final class CharacterReferences {

    /** Length of the longest name in the table, ';' included ("CounterClockwiseContourIntegral;"). */
    static final int LONGEST_NAME = 32;

    private static final Map<String, String> NAMED = load();

    /** Code points for numeric references 0x80-0x9F, in order. */
    private static final int[] WINDOWS_1252 = {
        0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
        0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178
    };

    private CharacterReferences() {}

    /** The text of a named reference given with or without its ';' (exactly as in the table), or null. */
    static String named(String name) {
        return NAMED.get(name);
    }

    /** Decodes every character reference in an attribute value. */
    static String decodeAttribute(String s) {
        int amp = s.indexOf('&');
        if (amp < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int copied = 0;
        while (amp >= 0) {
            out.append(s, copied, amp);
            int end = appendReference(s, amp, out);
            if (end < 0) {
                out.append('&');
                copied = amp + 1;
            } else {
                copied = end;
            }
            amp = s.indexOf('&', copied);
        }
        return out.append(s, copied, s.length()).toString();
    }

    /** Appends the reference starting at the '&' at {@code amp}; returns the index after it, or -1 if there is none. */
    private static int appendReference(String s, int amp, StringBuilder out) {
        int start = amp + 1;
        if (start < s.length() && s.charAt(start) == '#') {
            return appendNumeric(s, start + 1, out);
        }
        int run = start;
        while (run < s.length() && run - start < LONGEST_NAME && TextUtils.isAsciiAlnum(s.charAt(run))) {
            run++;
        }
        if (run < s.length() && s.charAt(run) == ';' && run + 1 - start <= LONGEST_NAME) {
            String value = NAMED.get(s.substring(start, run + 1));
            if (value != null) {
                out.append(value);
                return run + 1;
            }
        }
        // The longest name without ';'. In an attribute value it is not decoded when '=' or an ASCII
        // letter or digit follows; shorter names are then not tried.
        for (int end = run; end - start >= 2; end--) {
            String value = NAMED.get(s.substring(start, end));
            if (value == null) {
                continue;
            }
            if (end < s.length() && (s.charAt(end) == '=' || TextUtils.isAsciiAlnum(s.charAt(end)))) {
                return -1;
            }
            out.append(value);
            return end;
        }
        return -1;
    }

    private static int appendNumeric(String s, int afterHash, StringBuilder out) {
        boolean hex = afterHash < s.length() && (s.charAt(afterHash) == 'x' || s.charAt(afterHash) == 'X');
        int digits = hex ? afterHash + 1 : afterHash;
        int end = digits;
        long value = 0;
        while (end < s.length()
                && (hex ? TextUtils.isHexDigit(s.charAt(end)) : TextUtils.isAsciiDigit(s.charAt(end)))) {
            if (value <= 0x10FFFF) {
                value = value * (hex ? 16 : 10) + Character.digit(s.charAt(end), 16);
            }
            end++;
        }
        if (end == digits) {
            return -1;
        }
        if (end < s.length() && s.charAt(end) == ';') {
            end++;
        }
        out.appendCodePoint(codePointFor(value));
        return end;
    }

    /** The character a numeric reference stands for: U+FFFD for 0, surrogates and values past U+10FFFF. */
    static int codePointFor(long value) {
        if (value == 0 || value > 0x10FFFF || (value >= 0xD800 && value <= 0xDFFF)) {
            return TextUtils.REPLACEMENT_CHARACTER;
        }
        if (value >= 0x80 && value <= 0x9F) {
            return WINDOWS_1252[(int) value - 0x80];
        }
        return (int) value;
    }

    private static Map<String, String> load() {
        Map<String, String> map = new HashMap<>(4096);
        try (InputStream in = CharacterReferences.class.getResourceAsStream("html5-named-references.txt")) {
            if (in == null) {
                throw new IllegalStateException("missing resource html5-named-references.txt");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int space = line.indexOf(' ');
                StringBuilder value = new StringBuilder(2);
                int pos = space + 1;
                while (pos < line.length()) {
                    int next = line.indexOf(' ', pos);
                    if (next < 0) {
                        next = line.length();
                    }
                    value.appendCodePoint(Integer.parseInt(line, pos, next, 16));
                    pos = next + 1;
                }
                map.put(line.substring(0, space), value.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(map);
    }
}

package org.quarkpress.lib.phpserialize;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** Entry point: parse and write PHP {@code serialize()} data. */
public final class PhpSerialize {

    private static final Pattern NUMERIC_SCALAR = Pattern.compile("[bid]:-?[0-9.EINFAe+-]+;");

    private PhpSerialize() {}

    /**
     * Cheap heuristic: does this text look like PHP serialized data? Mirrors the behaviour
     * WordPress relies on when deciding whether an option value needs unserializing. It does not
     * validate the content; use {@link #parse(String)} for that.
     */
    public static boolean looksSerialized(String text) {
        if (text == null) {
            return false;
        }
        String s = text.strip();
        if (s.equals("N;")) {
            return true;
        }
        if (s.length() < 4 || s.charAt(1) != ':') {
            return false;
        }
        char last = s.charAt(s.length() - 1);
        if (last != ';' && last != '}') {
            return false;
        }
        return switch (s.charAt(0)) {
            case 's' -> s.endsWith("\";");
            case 'a', 'O', 'C' -> last == '}';
            case 'E' -> s.endsWith("\";");
            case 'b', 'i', 'd' -> NUMERIC_SCALAR.matcher(s).matches();
            default -> false;
        };
    }

    /** Parses serialized bytes with {@link ParseOptions#DEFAULTS}. */
    public static PhpValue parse(byte[] input) {
        return parse(input, ParseOptions.DEFAULTS);
    }

    public static PhpValue parse(byte[] input, ParseOptions options) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(options, "options");
        return new PhpParser(input, options).parseDocument();
    }

    /**
     * Parses serialized text. The text is first encoded with {@code options.charset()}, because
     * declared string lengths are byte counts in that encoding.
     */
    public static PhpValue parse(String input) {
        return parse(input, ParseOptions.DEFAULTS);
    }

    public static PhpValue parse(String input, ParseOptions options) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(options, "options");
        return parse(input.getBytes(options.charset()), options);
    }

    /** Serializes a value to bytes. Parsing the result yields an equal value. */
    public static byte[] serialize(PhpValue value) {
        Objects.requireNonNull(value, "value");
        return PhpWriter.write(value);
    }

    /**
     * Serializes a value and decodes the bytes as ISO-8859-1, so every byte maps to exactly one
     * char. Useful for tests and logging; use {@link #serialize(PhpValue)} for storage.
     */
    public static String serializeToString(PhpValue value) {
        return new String(serialize(value), StandardCharsets.ISO_8859_1);
    }
}

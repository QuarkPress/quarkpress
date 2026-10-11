package org.quarkpress.lib.phpserialize;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** Entry point: parse and write PHP {@code serialize()} data. */
public final class PhpSerialize {

    private static final Pattern NUMERIC_TAIL = Pattern.compile("-?[0-9.]+([eE][+-]?[0-9]+)?|-?INF|NAN");

    private PhpSerialize() {}

    /**
     * Cheap heuristic: does this text look like PHP serialized data? Derived from the format
     * grammar: a one-letter type tag, {@code :} (except for {@code N;}), and the closing token that
     * type requires. Surrounding whitespace is ignored because database drivers and copy/paste add
     * it. The intent matches what a WordPress-style option store needs when deciding whether a
     * value must be unserialized; it does not validate the content, use {@link #parse(String)} for
     * that.
     */
    public static boolean looksSerialized(String text) {
        if (text == null) {
            return false;
        }
        String s = text.strip();
        if (s.equals("N;")) {
            return true;
        }
        if (s.length() < 3 || s.charAt(1) != ':') {
            return false;
        }
        String tail = s.substring(2);
        return switch (s.charAt(0)) {
            case 's', 'E' -> tail.endsWith("\";");
            case 'a', 'O', 'C' -> tail.endsWith("}");
            case 'b', 'i', 'd' ->
                tail.endsWith(";")
                        && NUMERIC_TAIL
                                .matcher(tail.substring(0, tail.length() - 1))
                                .matches();
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
        PhpParser parser = new PhpParser(input, options);
        try {
            return parser.parseDocument();
        } catch (StackOverflowError e) {
            throw new PhpSerializeException("nesting too deep for the current thread stack", parser.position());
        }
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

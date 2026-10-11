package org.quarkpress.lib.phpserialize;

import java.util.Objects;

/** Entry point: parse and write PHP {@code serialize()} data. */
public final class PhpSerialize {

    private PhpSerialize() {}

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
}

package org.quarkpress.lib.phpserialize;

import java.util.regex.Pattern;

/**
 * PHP float, serialized as {@code d:1.5;}.
 *
 * <p>{@code raw} keeps the exact lexeme read by the parser so that writing the value back is
 * byte-identical. It is {@code null} for values created in Java. A non-null {@code raw} must be a
 * valid PHP float lexeme that denotes {@code value}. Equality considers only {@code value} (with
 * {@code NaN} equal to {@code NaN} and {@code 0.0} distinct from {@code -0.0}).
 */
public record PhpFloat(double value, String raw) implements PhpValue {

    /** PHP's float grammar: optional sign, digits with an optional fraction (or a bare fraction), optional exponent. */
    private static final Pattern LEXEME = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?|-?INF|NAN");

    public PhpFloat {
        if (raw != null && Double.compare(parseLexeme(raw), value) != 0) {
            throw new IllegalArgumentException("raw lexeme '" + raw + "' does not denote " + value);
        }
    }

    public PhpFloat(double value) {
        this(value, null);
    }

    /** True when {@code s} is a float lexeme PHP would have written. */
    static boolean isLexeme(String s) {
        return LEXEME.matcher(s).matches();
    }

    /**
     * Parses a PHP float lexeme.
     *
     * @throws IllegalArgumentException when the text is not a lexeme
     */
    static double parseLexeme(String s) {
        if (!isLexeme(s)) {
            throw new IllegalArgumentException("invalid float literal '" + s + "'");
        }
        return switch (s) {
            case "INF" -> Double.POSITIVE_INFINITY;
            case "-INF" -> Double.NEGATIVE_INFINITY;
            case "NAN" -> Double.NaN;
            default -> Double.parseDouble(s);
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PhpFloat other && Double.compare(value, other.value) == 0;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(value);
    }
}

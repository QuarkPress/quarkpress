package org.quarkpress.core.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.OptionalDouble;

/** PHP's conversions of option values, where WordPress code relies on them. */
final class PhpValues {

    /** Longest numeric prefix read; a longer number is treated as no number. */
    static final int MAX_NUMBER_TEXT = 64;

    /** PHP's {@code precision} setting, used when a float becomes a string. */
    private static final int PRECISION = 14;

    private PhpValues() {}

    /**
     * The leading number of {@code text} as PHP's {@code (float)} cast reads it: optional whitespace (space, tab, line
     * feed, carriage return, vertical tab, form feed), an optional sign, digits with an optional fraction (at least one
     * digit), an optional exponent; the rest is ignored. Empty when the text starts with no number (PHP then gives 0) or
     * the number is longer than {@value #MAX_NUMBER_TEXT} characters.
     */
    static OptionalDouble leadingFloat(String text) {
        int length = text.length();
        int i = 0;
        while (i < length && isPhpWhitespace(text.charAt(i))) {
            i++;
        }
        int start = i;
        // room for one character past the bound plus an exponent's "e" and sign that are not followed by a digit
        int limit = Math.min(length, start + MAX_NUMBER_TEXT + 3);
        if (i < limit && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        int digits = 0;
        while (i < limit && isDigit(text.charAt(i))) {
            i++;
            digits++;
        }
        if (i < limit && text.charAt(i) == '.') {
            int afterPoint = i + 1;
            int fraction = 0;
            while (afterPoint < limit && isDigit(text.charAt(afterPoint))) {
                afterPoint++;
                fraction++;
            }
            if (digits + fraction > 0) {
                i = afterPoint;
                digits += fraction;
            }
        }
        if (digits == 0) {
            return OptionalDouble.empty();
        }
        if (i < limit && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int exponent = i + 1;
            if (exponent < limit && (text.charAt(exponent) == '+' || text.charAt(exponent) == '-')) {
                exponent++;
            }
            int exponentDigits = 0;
            while (exponent < limit && isDigit(text.charAt(exponent))) {
                exponent++;
                exponentDigits++;
            }
            if (exponentDigits > 0) {
                i = exponent;
            }
        }
        if (i - start > MAX_NUMBER_TEXT) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Double.parseDouble(text.substring(start, i)));
    }

    /**
     * A text, number or boolean as PHP's string conversion writes it ({@code true} is "1", {@code false} is "",
     * {@code 5.0} is "5", floats with PHP's default precision of 14 digits); null for other values.
     */
    static String stringOf(JsonNode value) {
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "1" : "";
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return Long.toString(value.longValue());
        }
        if (value.isNumber()) {
            return floatString(value.doubleValue());
        }
        return null;
    }

    /** A float as PHP 8 writes it with {@code precision} 14 ({@code %.14G}, ".0" added to a bare exponent mantissa). */
    static String floatString(double value) {
        if (Double.isNaN(value)) {
            return "NAN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "INF" : "-INF";
        }
        if (value == 0) {
            return 1 / value < 0 ? "-0" : "0";
        }
        BigDecimal rounded = new BigDecimal(value).round(new MathContext(PRECISION, RoundingMode.HALF_EVEN));
        int exponent = rounded.precision() - rounded.scale() - 1;
        if (exponent < -4 || exponent >= PRECISION) {
            BigDecimal mantissa = rounded.movePointLeft(exponent).stripTrailingZeros();
            String digits = mantissa.toPlainString();
            if (!digits.contains(".")) {
                digits += ".0";
            }
            return digits + "E" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
        }
        return rounded.stripTrailingZeros().toPlainString();
    }

    static boolean isPhpWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}

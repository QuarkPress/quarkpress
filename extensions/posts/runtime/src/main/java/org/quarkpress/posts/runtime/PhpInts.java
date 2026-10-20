package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;

/** PHP 8's {@code (int)} cast of stored values, for comparisons WordPress makes with it. */
final class PhpInts {

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final double TWO_POW_63 = 9.223372036854775808E18;

    private PhpInts() {}

    /**
     * The value cast to an integer: an integral number as it is, a double truncated toward zero (one out of range
     * wraps around 64 bits, an infinite one or NaN is 0), text as {@link #of(String)}, true 1, false and null 0, an
     * empty array or object 0 and any other 1.
     */
    static long of(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return 0;
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? 1 : 0;
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.longValue();
        }
        if (value.isNumber()) {
            return ofDouble(value.doubleValue());
        }
        if (value.isTextual()) {
            return of(value.textValue());
        }
        if (value.isContainerNode()) {
            return value.isEmpty() ? 0 : 1;
        }
        return 0;
    }

    /**
     * Text cast to an integer: leading whitespace is skipped, then the longest leading decimal number counts (sign,
     * digits, fraction, exponent); an integer beyond the 64-bit range saturates, a number with a fraction or exponent
     * is truncated toward zero and saturates too, an infinite one is 0. Text without a leading number is 0.
     */
    static long of(String text) {
        int length = text.length();
        int i = 0;
        while (i < length && whitespace(text.charAt(i))) {
            i++;
        }
        int start = i;
        if (i < length && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        int integerStart = i;
        i = digits(text, i);
        boolean integerDigits = i > integerStart;
        boolean decimal = false;
        if (i < length && text.charAt(i) == '.') {
            int fractionEnd = digits(text, i + 1);
            if (integerDigits || fractionEnd > i + 1) {
                decimal = true;
                i = fractionEnd;
            }
        }
        if (!integerDigits && !decimal) {
            return 0;
        }
        if (i < length && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int exponent = i + 1;
            if (exponent < length && (text.charAt(exponent) == '+' || text.charAt(exponent) == '-')) {
                exponent++;
            }
            int exponentEnd = digits(text, exponent);
            if (exponentEnd > exponent) {
                decimal = true;
                i = exponentEnd;
            }
        }
        String number = text.substring(start, i);
        if (decimal) {
            double parsed = Double.parseDouble(number);
            // a finite value saturates as the Java cast does; an infinite one is 0
            return Double.isInfinite(parsed) ? 0 : (long) parsed;
        }
        BigInteger parsed = new BigInteger(number);
        return parsed.max(LONG_MIN).min(LONG_MAX).longValue();
    }

    /** A double cast to an integer: truncated; out of the 64-bit range it wraps around; NaN and infinities are 0. */
    private static long ofDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        if (value >= -TWO_POW_63 && value < TWO_POW_63) {
            return (long) value;
        }
        return new BigDecimal(value).toBigInteger().longValue();
    }

    private static int digits(String text, int from) {
        int i = from;
        while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
            i++;
        }
        return i;
    }

    /** The whitespace PHP skips before a number: space, tab, newline, carriage return, vertical tab, form feed. */
    private static boolean whitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000b' || c == '\f';
    }
}

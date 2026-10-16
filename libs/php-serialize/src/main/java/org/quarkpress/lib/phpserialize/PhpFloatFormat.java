package org.quarkpress.lib.phpserialize;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The text PHP 8 prints for a double. {@link #shortest} is what {@code serialize()} writes: the fewest digits
 * that read back as the same double. {@link #castToString} is a {@code (string)} cast, the value rounded to 14
 * significant digits, which is what WordPress stores for a float option. Both use plain notation for moderate
 * magnitudes and {@code D.DDDE±N} otherwise, and drop a zero fraction.
 */
final class PhpFloatFormat {

    /** Largest decimal point position written in plain notation by {@link #shortest}. */
    private static final int SHORTEST_PLAIN_LIMIT = 17;
    /** Largest decimal point position written in plain notation by {@link #castToString}. */
    private static final int CAST_PLAIN_LIMIT = 14;

    private static final int CAST_DIGITS = 14;

    private PhpFloatFormat() {}

    static String shortest(double value) {
        if (!Double.isFinite(value)) {
            return nonFinite(value);
        }
        return render(value, shortestDigits(Math.abs(value)), SHORTEST_PLAIN_LIMIT, false);
    }

    static String castToString(double value) {
        if (!Double.isFinite(value)) {
            return nonFinite(value);
        }
        BigDecimal exact = new BigDecimal(Math.abs(value));
        BigDecimal rounded = exact.round(new MathContext(CAST_DIGITS, RoundingMode.HALF_EVEN));
        return render(value, rounded, CAST_PLAIN_LIMIT, keepsRoundedZeros(exact, rounded));
    }

    private static String nonFinite(double value) {
        return Double.isNaN(value) ? "NAN" : value > 0 ? "INF" : "-INF";
    }

    /**
     * PHP keeps the zeros of the 14 rounded digits (in exponent notation) only for an exact tie at the 15th digit
     * that rounds down, below 1e15. A tie that rounds up drops the digits its carry zeroed, and from 1e15 on PHP
     * trims.
     */
    private static boolean keepsRoundedZeros(BigDecimal exact, BigDecimal rounded) {
        BigDecimal stripped = exact.stripTrailingZeros();
        return stripped.signum() != 0
                && stripped.precision() == CAST_DIGITS + 1
                && stripped.unscaledValue().mod(BigInteger.TEN).intValue() == 5
                && rounded.compareTo(exact) < 0
                && exact.compareTo(BigDecimal.valueOf(1, -15)) < 0;
    }

    /** The shortest decimal that reads back as {@code magnitude} (finite, not negative). */
    private static BigDecimal shortestDigits(double magnitude) {
        BigDecimal printed = new BigDecimal(Double.toString(magnitude));
        if (printed.stripTrailingZeros().precision() == 2) {
            // Double.toString never prints fewer than two digits, but one may already be enough
            BigDecimal one = new BigDecimal(magnitude).round(new MathContext(1, RoundingMode.HALF_EVEN));
            if (Double.parseDouble(one.toString()) == magnitude) {
                return one;
            }
        }
        return printed;
    }

    /**
     * Writes {@code magnitude} with the sign of {@code value}. With the digits D1D2... and point position p
     * (value = 0.D1D2... × 10^p), plain notation is used for -3 ≤ p ≤ plainLimit, exponent notation otherwise.
     * {@code keepCastZeros} pads the exponent form's digits back to {@link #CAST_DIGITS}.
     */
    private static String render(double value, BigDecimal magnitude, int plainLimit, boolean keepCastZeros) {
        StringBuilder out = new StringBuilder(26);
        if (value < 0 || (value == 0 && 1 / value < 0)) {
            out.append('-');
        }
        BigDecimal stripped = magnitude.stripTrailingZeros();
        String digits;
        int point;
        if (stripped.signum() == 0) {
            digits = "0";
            point = 1;
        } else {
            digits = stripped.unscaledValue().toString();
            point = digits.length() - stripped.scale();
        }
        if (point < -3 || point > plainLimit) {
            if (keepCastZeros && digits.length() < CAST_DIGITS) {
                digits = digits + "0".repeat(CAST_DIGITS - digits.length());
            }
            int exponent = point - 1;
            out.append(digits.charAt(0))
                    .append('.')
                    .append(digits.length() > 1 ? digits.substring(1) : "0")
                    .append('E')
                    .append(exponent < 0 ? '-' : '+')
                    .append(Math.abs(exponent));
        } else if (point <= 0) {
            out.append("0.").append("0".repeat(-point)).append(digits);
        } else if (point >= digits.length()) {
            out.append(digits).append("0".repeat(point - digits.length()));
        } else {
            out.append(digits, 0, point).append('.').append(digits, point, digits.length());
        }
        return out.toString();
    }
}

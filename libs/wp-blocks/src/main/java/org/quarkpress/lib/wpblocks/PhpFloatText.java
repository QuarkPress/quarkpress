package org.quarkpress.lib.wpblocks;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The text PHP's {@code json_encode} writes for a finite double ({@code serialize_precision = -1}): the shortest
 * digits that read back as the same double, plain notation while the decimal point position p (value =
 * 0.DIGITS × 10^p) satisfies -3 ≤ p ≤ 17, otherwise {@code D.DDDe±N} with at least one fractional digit, and no
 * {@code .0} on integral values. The same rule as lib-php-serialize's writer with a lowercase {@code e}; the two
 * libraries share no code.
 */
final class PhpFloatText {

    private static final int PLAIN_LIMIT = 17;

    private PhpFloatText() {}

    static String of(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("not a finite number: " + value);
        }
        StringBuilder out = new StringBuilder(26);
        if (value < 0 || (value == 0 && 1 / value < 0)) {
            out.append('-');
        }
        BigDecimal stripped = shortestDigits(Math.abs(value)).stripTrailingZeros();
        String digits;
        int point;
        if (stripped.signum() == 0) {
            digits = "0";
            point = 1;
        } else {
            digits = stripped.unscaledValue().toString();
            point = digits.length() - stripped.scale();
        }
        if (point < -3 || point > PLAIN_LIMIT) {
            int exponent = point - 1;
            out.append(digits.charAt(0))
                    .append('.')
                    .append(digits.length() > 1 ? digits.substring(1) : "0")
                    .append('e')
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

    /** Double.toString prints at least two significant digits; one may already read back as the same double. */
    private static BigDecimal shortestDigits(double magnitude) {
        BigDecimal printed = new BigDecimal(Double.toString(magnitude));
        if (printed.stripTrailingZeros().precision() == 2) {
            BigDecimal one = new BigDecimal(magnitude).round(new MathContext(1, RoundingMode.HALF_EVEN));
            if (Double.parseDouble(one.toString()) == magnitude) {
                return one;
            }
        }
        return printed;
    }
}

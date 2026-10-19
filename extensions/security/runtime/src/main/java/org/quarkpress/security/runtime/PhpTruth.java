package org.quarkpress.security.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/** PHP's truthiness and integer cast for stored option values, as WordPress's capability rules read them. */
public final class PhpTruth {

    private static final BigDecimal MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal MIN = BigDecimal.valueOf(Long.MIN_VALUE);

    private PhpTruth() {}

    /** False for absent, null, false, zero, "", "0" and empty arrays and objects; true otherwise. */
    public static boolean truthy(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.decimalValue().signum() != 0;
        }
        if (value.isTextual()) {
            String text = value.textValue();
            return !text.isEmpty() && !text.equals("0");
        }
        return !value.isEmpty();
    }

    /**
     * PHP's {@code (int)} of a stored value: numbers truncated toward zero, booleans 0 or 1, text by its leading
     * number (after leading whitespace: an optional sign, digits, an optional fraction and exponent, read as a decimal
     * and truncated; none gives 0), arrays and objects 0 when empty and 1 otherwise, absent and null 0. A value beyond
     * the 64-bit range saturates at the nearest limit, where PHP would wrap a number (a deliberate difference).
     */
    public static long intValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return 0;
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? 1 : 0;
        }
        if (value.isNumber()) {
            return saturate(value.decimalValue());
        }
        if (value.isTextual()) {
            return leadingInteger(value.textValue());
        }
        return value.isEmpty() ? 0 : 1;
    }

    private static long saturate(BigDecimal number) {
        if (number.compareTo(MAX) > 0) {
            return Long.MAX_VALUE;
        }
        if (number.compareTo(MIN) < 0) {
            return Long.MIN_VALUE;
        }
        return number.longValue();
    }

    private static long leadingInteger(String text) {
        int i = 0;
        while (i < text.length() && " \t\n\r\u000B\f".indexOf(text.charAt(i)) >= 0) {
            i++;
        }
        int start = i;
        if (i < text.length() && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        int digitsStart = i;
        i = skipDigits(text, i);
        int integerDigits = i - digitsStart;
        int fractionDigits = 0;
        if (i < text.length() && text.charAt(i) == '.') {
            int afterDot = skipDigits(text, i + 1);
            fractionDigits = afterDot - (i + 1);
            if (integerDigits > 0 || fractionDigits > 0) {
                i = afterDot;
            }
        }
        if (integerDigits == 0 && fractionDigits == 0) {
            return 0;
        }
        int mantissaEnd = i;
        if (i < text.length() && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < text.length() && (text.charAt(j) == '+' || text.charAt(j) == '-')) {
                j++;
            }
            int exponentEnd = skipDigits(text, j);
            if (exponentEnd > j) {
                i = exponentEnd;
            }
        }
        String numeric = text.substring(start, i);
        if (numeric.startsWith("+")) {
            numeric = numeric.substring(1);
        }
        try {
            return saturate(new BigDecimal(numeric));
        } catch (NumberFormatException | ArithmeticException tooBig) {
            // an exponent beyond what BigDecimal holds: only the sign and whether the mantissa is zero matter
            BigDecimal mantissa =
                    new BigDecimal(text.substring(start, mantissaEnd).replace("+", ""));
            boolean negativeExponent = text.charAt(mantissaEnd + 1) == '-';
            if (mantissa.signum() == 0 || negativeExponent) {
                return 0;
            }
            return mantissa.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static int skipDigits(String text, int from) {
        int i = from;
        while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
            i++;
        }
        return i;
    }
}

package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PHP value semantics over Jackson nodes, as WordPress's style engine sees its JSON-decoded arrays: emptiness,
 * truthiness, string conversion, trimming and array access. {@code null}, a {@code NullNode} and a missing node are
 * all PHP {@code null}.
 */
final class Php {

    /** PHP's {@code precision} setting, which its string cast of a float uses. */
    private static final MathContext PRECISION = new MathContext(14, RoundingMode.HALF_EVEN);

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private Php() {}

    /**
     * PHP {@code empty()}: null, false, {@code ""}, {@code "0"}, integer 0, float +/-0.0 and an empty array are empty.
     */
    static boolean isEmpty(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return true;
        }
        if (v.isTextual()) {
            return isEmpty(v.asText());
        }
        if (v.isBoolean()) {
            return !v.booleanValue();
        }
        if (v.isNumber()) {
            if (v.isIntegralNumber()) {
                return v.bigIntegerValue().signum() == 0;
            }
            if (v.isBigDecimal()) {
                return v.decimalValue().signum() == 0;
            }
            return v.doubleValue() == 0.0;
        }
        if (v.isContainerNode()) {
            return v.isEmpty();
        }
        return false;
    }

    /** PHP {@code empty()} of a string: {@code null}, {@code ""} and {@code "0"}. */
    static boolean isEmpty(String s) {
        return s == null || s.isEmpty() || s.equals("0");
    }

    static boolean isTruthy(JsonNode v) {
        return !isEmpty(v);
    }

    /** A style value counts when it is the string {@code "0"} or is not PHP-empty. */
    static boolean isValidStyleValue(JsonNode v) {
        if (v != null && v.isTextual() && v.asText().equals("0")) {
            return true;
        }
        return !isEmpty(v);
    }

    /** A PHP array: a JSON object or a JSON list. */
    static boolean isArray(JsonNode v) {
        return v instanceof ObjectNode || v instanceof ArrayNode;
    }

    /** {@code === true}. */
    static boolean isStrictTrue(JsonNode v) {
        return v != null && v.isBoolean() && v.booleanValue();
    }

    /** PHP {@code trim()}: space, TAB, LF, CR, NUL and VT at both ends (not FF, not Unicode spaces). */
    static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isTrimmed(s.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmed(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    /** PHP {@code rtrim()} with the characters of {@link #trim}. */
    static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && isTrimmed(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static boolean isTrimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u0000' || c == '\u000b';
    }

    /**
     * PHP's string conversion of a value: text as it is, an integer in decimal, true {@code "1"}, false and null
     * {@code ""}, a float as PHP casts it, an array {@code "Array"}.
     */
    static String toPhpString(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "";
        }
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isBoolean()) {
            return v.booleanValue() ? "1" : "";
        }
        if (v.isContainerNode()) {
            return "Array";
        }
        if (v.isIntegralNumber()) {
            BigInteger n = v.bigIntegerValue();
            // a JSON integer beyond PHP's integer range is decoded as a float
            if (n.compareTo(LONG_MIN) < 0 || n.compareTo(LONG_MAX) > 0) {
                return floatString(n.doubleValue());
            }
            return n.toString();
        }
        if (v.isNumber()) {
            return floatString(v.doubleValue());
        }
        return v.asText();
    }

    /**
     * PHP's string cast of a float: 14 significant digits, trailing zeros dropped; exponent notation
     * ({@code 1.0E+25}, {@code 1.5E-7}) when the decimal exponent is below -4 or at least 15.
     */
    static String floatString(double d) {
        if (Double.isNaN(d)) {
            return "NAN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "INF" : "-INF";
        }
        String sign = (d < 0 || (d == 0.0 && 1 / d < 0)) ? "-" : "";
        if (d == 0.0) {
            return sign + "0";
        }
        BigDecimal rounded = new BigDecimal(Math.abs(d)).round(PRECISION).stripTrailingZeros();
        String digits = rounded.unscaledValue().toString();
        // the value is 0.<digits> x 10^point
        int point = digits.length() - rounded.scale();
        StringBuilder out = new StringBuilder(sign);
        if (point < -3 || point > 14) {
            out.append(digits.charAt(0)).append('.');
            out.append(digits.length() == 1 ? "0" : digits.substring(1));
            int exponent = point - 1;
            out.append('E').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
        } else if (point <= 0) {
            out.append("0.").append("0".repeat(-point)).append(digits);
        } else if (point >= digits.length()) {
            out.append(digits).append("0".repeat(point - digits.length()));
        } else {
            out.append(digits, 0, point).append('.').append(digits, point, digits.length());
        }
        return out.toString();
    }

    /** The entries of a PHP array in order: object fields, or list indexes as decimal strings. */
    static Iterable<Map.Entry<String, JsonNode>> entries(JsonNode array) {
        if (array instanceof ObjectNode object) {
            return object.properties();
        }
        List<Map.Entry<String, JsonNode>> list = new ArrayList<>();
        if (array instanceof ArrayNode items) {
            for (int i = 0; i < items.size(); i++) {
                list.add(new AbstractMap.SimpleImmutableEntry<>(Integer.toString(i), items.get(i)));
            }
        }
        return list;
    }

    /**
     * {@code $array[$key]} when set: an object field (a present null is a {@code NullNode}), or a list element when
     * the key is a canonical decimal index; otherwise {@code null}.
     */
    static JsonNode get(JsonNode array, String key) {
        if (array instanceof ObjectNode object) {
            return object.get(key);
        }
        if (array instanceof ArrayNode items) {
            int index = canonicalIndex(key);
            return index >= 0 && index < items.size() ? items.get(index) : null;
        }
        return null;
    }

    /** The index a canonical non-negative decimal key names ({@code "0"}, {@code "12"}), or -1. */
    private static int canonicalIndex(String key) {
        int n = key.length();
        if (n == 0 || n > 10 || (n > 1 && key.charAt(0) == '0')) {
            return -1;
        }
        long value = 0;
        for (int i = 0; i < n; i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }
}

package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * PHP 8's view of request values, which WordPress's argument checks rely on: numeric strings, the integer, float,
 * boolean and string conversions, and which JSON values PHP holds as arrays. A JSON array and a JSON object are both
 * PHP arrays; an object whose keys are all canonical integers (as a query's {@code a[]=...} gives) is a list.
 */
public final class PhpValues {

    private static final double TWO_POW_63 = 0x1p63;
    private static final double TWO_POW_64 = 0x1p64;
    private static final MathContext PRECISION = new MathContext(14, RoundingMode.HALF_EVEN);

    private PhpValues() {}

    /** PHP's {@code is_numeric}: numbers, and numeric strings. */
    public static boolean isNumeric(JsonNode value) {
        if (value == null) {
            return false;
        }
        if (value.isNumber()) {
            return true;
        }
        return value.isTextual() && isNumeric(value.textValue());
    }

    /**
     * PHP 8's numeric string: optional leading and trailing whitespace (space, tab, line feed, carriage return,
     * vertical tab, form feed), an optional sign, digits with an optional fraction or a fraction alone, and an optional
     * exponent; nothing else (no hexadecimal, binary or underscores).
     */
    public static boolean isNumeric(String text) {
        int end = numericPrefix(text, 0);
        if (end < 0) {
            return false;
        }
        while (end < text.length() && whitespace(text.charAt(end))) {
            end++;
        }
        return end == text.length();
    }

    /** PHP's {@code (float)}: numbers as they are, strings by their leading numeric part (none: 0). */
    public static double toDouble(JsonNode value) {
        if (value == null || value.isNull()) {
            return 0.0;
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? 1.0 : 0.0;
        }
        if (value.isTextual()) {
            return stringToDouble(value.textValue());
        }
        return value.isEmpty() ? 0.0 : 1.0;
    }

    /**
     * PHP's {@code (int)}: an integer as it is; a float truncated, 0 when not finite and taken modulo 2^64 beyond the
     * range; a string by its leading numeric part, saturating at the range's ends (a non-finite value gives 0); true
     * 1; null 0; an array 0 when empty, else 1.
     */
    public static long toLong(JsonNode value) {
        if (value == null || value.isNull()) {
            return 0;
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.longValue();
        }
        if (value.isNumber()) {
            return doubleToLong(value.doubleValue());
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? 1 : 0;
        }
        if (value.isTextual()) {
            return stringToLong(value.textValue());
        }
        return value.isEmpty() ? 0 : 1;
    }

    /** PHP's conversion of a float to an integer: 0 when not finite, truncated in range, else modulo 2^64. */
    public static long doubleToLong(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        if (value >= -TWO_POW_63 && value < TWO_POW_63) {
            return (long) value;
        }
        double mod = value % TWO_POW_64;
        if (mod < 0) {
            mod += TWO_POW_64;
        }
        if (mod >= TWO_POW_63) {
            mod -= TWO_POW_64;
        }
        return (long) mod;
    }

    /** PHP's {@code (bool)}, with WordPress's boolean sanitizing: the string "false" in any case is false too. */
    public static boolean toBoolean(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.isIntegralNumber() ? value.longValue() != 0 : value.doubleValue() != 0.0;
        }
        if (value.isTextual()) {
            String text = value.textValue();
            return !(text.isEmpty() || text.equals("0") || text.equalsIgnoreCase("false"));
        }
        return !value.isEmpty();
    }

    /**
     * PHP's {@code empty()}: absent, null, false, 0, 0.0, {@code ""}, {@code "0"} and an empty array (WordPress's
     * boolean sanitizing does not apply: the string {@code "false"} is not empty).
     */
    public static boolean isEmpty(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return true;
        }
        if (value.isBoolean()) {
            return !value.booleanValue();
        }
        if (value.isNumber()) {
            return value.isIntegralNumber() ? value.bigIntegerValue().signum() == 0 : value.doubleValue() == 0.0;
        }
        if (value.isTextual()) {
            return value.textValue().isEmpty() || value.textValue().equals("0");
        }
        return value.isContainerNode() && value.isEmpty();
    }

    /** PHP's {@code (string)}: floats with 14 significant digits, true "1", false and null "", an array "Array". */
    public static String toPhpString(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
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
            return doubleToString(value.doubleValue());
        }
        return "Array";
    }

    /**
     * PHP's {@code (string)} of a float (its {@code precision} setting of 14): at most 14 significant digits, in
     * exponent form ({@code 1.0E+25}, {@code 1.5E-7}) when the exponent is below -4 or at least 14.
     */
    public static String doubleToString(double value) {
        if (Double.isNaN(value)) {
            return "NAN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "INF" : "-INF";
        }
        if (value == 0.0) {
            return (1.0 / value) < 0 ? "-0" : "0";
        }
        BigDecimal rounded = new BigDecimal(value).round(PRECISION).stripTrailingZeros();
        String digits = rounded.unscaledValue().abs().toString();
        int exponent = digits.length() - rounded.scale() - 1;
        String sign = rounded.signum() < 0 ? "-" : "";
        if (exponent < -4 || exponent >= 14) {
            String mantissa = digits.length() == 1 ? digits + ".0" : digits.charAt(0) + "." + digits.substring(1);
            return sign + mantissa + "E" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
        }
        return sign + rounded.abs().toPlainString();
    }

    /** Whether PHP holds the value as an array: a JSON array or object. */
    public static boolean isPhpArray(JsonNode value) {
        return value != null && (value.isArray() || value.isObject());
    }

    /** Whether every key of the object is a canonical integer, which PHP turns into an integer key. */
    public static boolean hasIntegerKeys(ObjectNode object) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            if (!integerKey(names.next())) {
                return false;
            }
        }
        return true;
    }

    /** A canonical decimal integer in the 64-bit range ("0", "-5"; not "05", "-0", "+5" or " 5"). */
    public static boolean integerKey(String key) {
        int length = key.length();
        int start = length > 0 && key.charAt(0) == '-' ? 1 : 0;
        if (start == length || length - start > 19) {
            return false;
        }
        if (key.charAt(start) == '0') {
            return length == 1;
        }
        for (int i = start; i < length; i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        if (length - start < 19) {
            return true;
        }
        String limit = start == 1 ? "9223372036854775808" : "9223372036854775807";
        return key.substring(start).compareTo(limit) <= 0;
    }

    /** The values of a PHP array in its order (a JSON array's items, or a JSON object's values). */
    public static List<JsonNode> values(JsonNode array) {
        List<JsonNode> values = new ArrayList<>(array.size());
        if (array.isArray()) {
            array.forEach(values::add);
        } else {
            for (Iterator<Map.Entry<String, JsonNode>> it = array.fields(); it.hasNext(); ) {
                values.add(it.next().getValue());
            }
        }
        return values;
    }

    /**
     * WordPress's list parsing of a scalar: the text split on runs of commas and ASCII whitespace (space, tab, line
     * feed, carriage return, vertical tab, form feed), empty pieces dropped. Adds the characters looked at to
     * {@code work[0]}: each once.
     */
    public static List<String> parseList(String text, long[] work) {
        List<String> pieces = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            work[0]++;
            char c = text.charAt(i);
            boolean separator = c == ',' || whitespace(c);
            if (separator) {
                if (start >= 0) {
                    pieces.add(text.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) {
            pieces.add(text.substring(start));
        }
        return pieces;
    }

    /** {@link #parseList(String, long[])} without counting. */
    public static List<String> parseList(String text) {
        return parseList(text, new long[1]);
    }

    /** PHP's whitespace for numeric strings and WordPress's list splitting. */
    static boolean whitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == '\f';
    }

    /**
     * The end of the numeric part starting at {@code from} after leading whitespace: sign, digits, fraction and a
     * complete exponent; -1 when there is no digit.
     */
    private static int numericPrefix(String text, int from) {
        int i = from;
        int length = text.length();
        while (i < length && whitespace(text.charAt(i))) {
            i++;
        }
        if (i < length && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        int digits = 0;
        while (i < length && digit(text.charAt(i))) {
            i++;
            digits++;
        }
        if (i < length && text.charAt(i) == '.') {
            int afterDot = i + 1;
            int fraction = 0;
            while (afterDot + fraction < length && digit(text.charAt(afterDot + fraction))) {
                fraction++;
            }
            if (digits + fraction > 0) {
                i = afterDot + fraction;
                digits += fraction;
            }
        }
        if (digits == 0) {
            return -1;
        }
        if (i < length && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < length && (text.charAt(j) == '+' || text.charAt(j) == '-')) {
                j++;
            }
            int exponentStart = j;
            while (j < length && digit(text.charAt(j))) {
                j++;
            }
            if (j > exponentStart) {
                i = j;
            }
        }
        return i;
    }

    private static double stringToDouble(String text) {
        int end = numericPrefix(text, 0);
        if (end < 0) {
            return 0.0;
        }
        return Double.parseDouble(text.substring(skipWhitespace(text), end));
    }

    private static long stringToLong(String text) {
        int end = numericPrefix(text, 0);
        if (end < 0) {
            return 0;
        }
        int start = skipWhitespace(text);
        boolean integral = true;
        for (int i = start; i < end && integral; i++) {
            char c = text.charAt(i);
            integral = c != '.' && c != 'e' && c != 'E';
        }
        String number = text.substring(start, end);
        if (integral) {
            try {
                return Long.parseLong(number.charAt(0) == '+' ? number.substring(1) : number);
            } catch (NumberFormatException overflow) {
                return number.charAt(0) == '-' ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
        }
        double parsed = Double.parseDouble(number);
        if (!Double.isFinite(parsed)) {
            return 0;
        }
        if (parsed >= TWO_POW_63) {
            return Long.MAX_VALUE;
        }
        if (parsed < -TWO_POW_63) {
            return Long.MIN_VALUE;
        }
        return (long) parsed;
    }

    private static int skipWhitespace(String text) {
        int i = 0;
        while (i < text.length() && whitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }
}

package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;

/**
 * JSON text as PHP's {@code json_encode} writes it with its default flags: slashes escaped ({@code \/}), every
 * character outside ASCII written as a backslash-u escape of each of its UTF-16 units (lower-case hex), the short
 * escapes {@code \b \f \n \r \t}, the backslash-u form for the other control characters, no spaces, and doubles in
 * PHP's shortest form (an integral double without a fraction, exponents below 1e-4 and from 1e17 up as
 * {@code 1.0e+17}).
 */
final class PhpJsonText {

    private static final String HEX = "0123456789abcdef";

    private PhpJsonText() {}

    /** The value as PHP encodes it; a number that is not finite is refused with {@link IllegalArgumentException}. */
    static String encode(JsonNode value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(JsonNode value, StringBuilder out) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            out.append("null");
        } else if (value.isBoolean()) {
            out.append(value.booleanValue());
        } else if (value.isTextual()) {
            writeText(value.textValue(), out);
        } else if (value.isIntegralNumber()) {
            out.append(value.asText());
        } else if (value.isNumber()) {
            out.append(phpDouble(value.doubleValue()));
        } else if (value.isArray()) {
            out.append('[');
            boolean first = true;
            for (JsonNode item : value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else if (value.isObject()) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeText(entry.getKey(), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + value.getNodeType());
        }
    }

    private static void writeText(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '/' -> out.append("\\/");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c >= 0x80) {
                        out.append("\\u")
                                .append(HEX.charAt((c >> 12) & 0xF))
                                .append(HEX.charAt((c >> 8) & 0xF))
                                .append(HEX.charAt((c >> 4) & 0xF))
                                .append(HEX.charAt(c & 0xF));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * PHP's double in JSON: the shortest decimal that reads back as the same double, plain when its decimal exponent
     * is between -4 and 16, else as {@code d.ddde+XX}; an integral double has no fraction.
     */
    static String phpDouble(double number) {
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("PHP cannot encode " + number);
        }
        if (number == 0) {
            return 1 / number < 0 ? "-0" : "0";
        }
        BigDecimal decimal = new BigDecimal(Double.toString(number)).stripTrailingZeros();
        int pointAt = decimal.precision() - decimal.scale();
        if (pointAt < -3 || pointAt > 17) {
            String digits = decimal.unscaledValue().abs().toString();
            StringBuilder text = new StringBuilder();
            if (decimal.signum() < 0) {
                text.append('-');
            }
            text.append(digits.charAt(0)).append('.');
            text.append(digits.length() == 1 ? "0" : digits.substring(1));
            int exponent = pointAt - 1;
            text.append('e').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
            return text.toString();
        }
        return decimal.toPlainString();
    }
}

package org.quarkpress.lib.wpblocks;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Writes block attributes as WordPress's block serializer does: PHP's {@code json_encode} output (slashes and
 * non-ASCII left as they are, U+2028 and U+2029 escaped, lowercase hex escapes, PHP float text) with the comment
 * safety escapes applied on top, so that the text can never end the HTML comment: backslash, two hyphens, angle
 * brackets, ampersand and quote are written as backslash-u escapes (005c, 002d 002d, 003c, 003e, 0026, 0022).
 * The output still parses as JSON for the same value. Non-finite numbers, which JSON cannot carry, are written as
 * {@code null}.
 */
final class WordPressJson {

    private WordPressJson() {}

    static String write(JsonNode node) {
        StringBuilder out = new StringBuilder();
        appendValue(out, node);
        return out.toString();
    }

    private static void appendValue(StringBuilder out, JsonNode node) {
        switch (node.getNodeType()) {
            case OBJECT -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    appendString(out, e.getKey());
                    out.append(':');
                    appendValue(out, e.getValue());
                }
                out.append('}');
            }
            case ARRAY -> {
                out.append('[');
                for (int i = 0; i < node.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    appendValue(out, node.get(i));
                }
                out.append(']');
            }
            case STRING -> appendString(out, node.textValue());
            case BOOLEAN -> out.append(node.booleanValue());
            case NULL -> out.append("null");
            case NUMBER -> appendNumber(out, node);
            default -> throw new IllegalArgumentException("not a JSON value: " + node.getNodeType());
        }
    }

    private static void appendNumber(StringBuilder out, JsonNode n) {
        if (n.isIntegralNumber()) {
            out.append(n.bigIntegerValue());
            return;
        }
        double d = n.doubleValue();
        out.append(Double.isFinite(d) ? PhpFloatText.of(d) : "null");
    }

    private static void appendString(StringBuilder out, String s) {
        out.append('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\u0022");
                case '\\' -> out.append("\\u005c");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                case '-' -> {
                    if (i + 1 < n && s.charAt(i + 1) == '-') {
                        out.append("\\u002d\\u002d");
                        i++;
                    } else {
                        out.append('-');
                    }
                }
                default -> {
                    if (c < 0x20 || isLoneSurrogate(s, i)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** A surrogate that is not half of a pair (possible only in API-built text). */
    private static boolean isLoneSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return i == 0 || !Character.isHighSurrogate(s.charAt(i - 1));
        }
        return false;
    }
}

package org.quarkpress.lib.phpserialize;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Minimal compact JSON writer for {@link PhpValue} trees. No external dependency. */
final class PhpJson {

    private PhpJson() {}

    /** Resolves back-references first ({@link PhpReferences}), then writes. */
    static String write(PhpValue value) {
        StringBuilder sb = new StringBuilder();
        append(sb, PhpReferences.resolve(value));
        return sb.toString();
    }

    private static void append(StringBuilder sb, PhpValue value) {
        switch (value) {
            case PhpNull n -> sb.append("null");
            case PhpBool b -> sb.append(b.value());
            case PhpInt i -> sb.append(i.value());
            case PhpFloat f -> appendDouble(sb, f.value());
            case PhpString s -> appendString(sb, s.text());
            case PhpArray a -> {
                if (a.isList()) {
                    appendList(sb, a);
                } else {
                    appendObject(sb, a);
                }
            }
            case PhpObject o -> appendProperties(sb, PhpConversions.visibleProperties(o.properties()));
            case PhpCustomObject c -> appendString(sb, new String(c.payload(), StandardCharsets.UTF_8));
            case PhpReference r -> sb.append("null");
            case PhpEnum e -> appendString(sb, e.className() + ":" + e.caseName());
        }
    }

    private static void appendList(StringBuilder sb, PhpArray array) {
        sb.append('[');
        boolean first = true;
        for (PhpValue v : array.entries().values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            append(sb, v);
        }
        sb.append(']');
    }

    private static void appendObject(StringBuilder sb, PhpArray array) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<PhpArray.Key, PhpValue> e : array.entries().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendString(sb, PhpConversions.keyToString(e.getKey()));
            sb.append(':');
            append(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void appendProperties(StringBuilder sb, Map<String, PhpValue> properties) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, PhpValue> e : properties.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendString(sb, e.getKey());
            sb.append(':');
            append(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void appendDouble(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            sb.append("null");
            return;
        }
        // json_encode() writes the same digits as serialize(), with a lowercase exponent marker
        sb.append(PhpFloatFormat.shortest(d).replace('E', 'e'));
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}

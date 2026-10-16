package org.quarkpress.lib.phpserialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.quarkpress.testing.wporacle.PhpTree;

/** Turns PHP values into oracle trees and write-case trees into PHP values. */
final class PhpTreeEncoder {

    private PhpTreeEncoder() {}

    /** The tree of {@code value} with back-references resolved; a reference cycle gives the cycle marker. */
    static ObjectNode encodeResolved(PhpValue value) {
        PhpValue resolved;
        try {
            resolved = PhpReferences.resolve(value);
        } catch (PhpSerializeException e) {
            if ("cyclic reference".equals(e.getMessage())) {
                return PhpTree.cycle();
            }
            throw e;
        }
        return encode(resolved);
    }

    static ObjectNode encode(PhpValue value) {
        return switch (value) {
            case PhpNull n -> PhpTree.nul();
            case PhpBool b -> PhpTree.bool(b.value());
            case PhpInt i -> PhpTree.integer(i.value());
            case PhpFloat f -> PhpTree.floating(PhpFloatFormat.shortest(f.value()));
            case PhpString s -> PhpTree.string(s.bytes());
            case PhpArray a -> PhpTree.array(entries(a));
            case PhpObject o -> PhpTree.object(o.className(), entries(o.properties()));
            case PhpCustomObject c -> PhpTree.custom(c.className(), c.payload());
            case PhpEnum e -> PhpTree.enumCase(e.className(), e.caseName());
            case PhpReference r -> PhpTree.nul(); // only a dangling one is left after resolve()
        };
    }

    private static List<PhpTree.Entry> entries(PhpArray array) {
        List<PhpTree.Entry> entries = new ArrayList<>(array.size());
        for (Map.Entry<PhpArray.Key, PhpValue> e : array.entries().entrySet()) {
            ObjectNode key = switch (e.getKey()) {
                case PhpArray.Key.Int i -> PhpTree.integer(i.value());
                case PhpArray.Key.Str s -> PhpTree.string(s.value().bytes());
            };
            entries.add(new PhpTree.Entry(key, encode(e.getValue())));
        }
        return entries;
    }

    /** Builds the value a write case describes, as bootstrap.php's qp_untree() does: put for keys, add for appends. */
    static PhpValue decode(JsonNode tree) {
        return switch (PhpTree.type(tree)) {
            case "null" -> PhpNull.INSTANCE;
            case "bool" -> PhpBool.of(tree.get("v").booleanValue());
            case "int" -> new PhpInt(Long.parseLong(tree.get("v").asText()));
            case "float" -> new PhpFloat(parseFloat(tree.get("v").asText()));
            case "string" -> PhpString.ofBytes(PhpTree.bytes(tree));
            case "array" -> decodeEntries(tree.get("e"));
            case "object" -> new PhpObject(tree.get("c").asText(), decodeEntries(tree.get("e")));
            default -> throw new IllegalArgumentException("cannot build a value of type " + PhpTree.type(tree));
        };
    }

    private static PhpArray decodeEntries(JsonNode pairs) {
        PhpArray.Builder builder = PhpArray.builder();
        for (JsonNode pair : pairs) {
            JsonNode key = pair.get(0);
            PhpValue value = decode(pair.get(1));
            switch (PhpTree.type(key)) {
                case "append" -> builder.add(value);
                case "int" -> builder.put(Long.parseLong(key.get("v").asText()), value);
                case "string" -> builder.put(PhpArray.Key.of(PhpString.ofBytes(PhpTree.bytes(key))), value);
                default -> throw new IllegalArgumentException("bad key type " + PhpTree.type(key));
            }
        }
        return builder.build();
    }

    static double parseFloat(String text) {
        return switch (text) {
            case "INF" -> Double.POSITIVE_INFINITY;
            case "-INF" -> Double.NEGATIVE_INFINITY;
            case "NAN" -> Double.NaN;
            default -> Double.parseDouble(text);
        };
    }

    /** Text that PHP's {@code (float)} cast reads back as exactly this double. */
    static String floatText(double value) {
        if (Double.isNaN(value)) {
            return "NAN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "INF" : "-INF";
        }
        return Double.toString(value);
    }

    /** True when the value holds a {@code C:} object or an enum, which PHP cannot rebuild for generated data. */
    static boolean mentionsUnavailableClasses(PhpValue value) {
        return switch (value) {
            case PhpCustomObject c -> true;
            case PhpEnum e -> true;
            case PhpArray a -> a.entries().values().stream().anyMatch(PhpTreeEncoder::mentionsUnavailableClasses);
            case PhpObject o -> mentionsUnavailableClasses(o.properties());
            default -> false;
        };
    }
}

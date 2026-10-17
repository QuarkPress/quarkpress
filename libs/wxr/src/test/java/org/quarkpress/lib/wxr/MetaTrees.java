package org.quarkpress.lib.wxr;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.quarkpress.lib.phpserialize.MaybeUnserializeResult;
import org.quarkpress.lib.phpserialize.PhpArray;
import org.quarkpress.lib.phpserialize.PhpBool;
import org.quarkpress.lib.phpserialize.PhpCustomObject;
import org.quarkpress.lib.phpserialize.PhpEnum;
import org.quarkpress.lib.phpserialize.PhpFloat;
import org.quarkpress.lib.phpserialize.PhpInt;
import org.quarkpress.lib.phpserialize.PhpNull;
import org.quarkpress.lib.phpserialize.PhpObject;
import org.quarkpress.lib.phpserialize.PhpReference;
import org.quarkpress.lib.phpserialize.PhpSerialize;
import org.quarkpress.lib.phpserialize.PhpString;
import org.quarkpress.lib.phpserialize.PhpValue;
import org.quarkpress.testing.wporacle.PhpTree;

/** What the importer stores for a meta value, as the oracle's typed tree (bootstrap.php's qp_tree). */
final class MetaTrees {

    private MetaTrees() {}

    static ObjectNode of(MaybeUnserializeResult result) {
        return switch (result) {
            case MaybeUnserializeResult.NotSerialized n ->
                PhpTree.string(n.value().bytes());
            case MaybeUnserializeResult.Parsed p -> {
                List<PhpValue> slots = new ArrayList<>();
                number(p.value(), slots);
                try {
                    yield new Encoder(slots).encode(p.value());
                } catch (Cycle c) {
                    yield PhpTree.cycle(); // qp_safe_tree's answer for a value that contains itself
                }
            }
            case MaybeUnserializeResult.Failed f -> PhpTree.bool(false);
        };
    }

    /**
     * Numbers the values as PHP's unserialize does for {@code R:} and {@code r:}: 1-based, in order of appearance,
     * every value but an {@code R:} reference itself (array keys are not values).
     */
    private static void number(PhpValue value, List<PhpValue> slots) {
        if (!(value instanceof PhpReference r && r.kind() == PhpReference.Kind.REFERENCE)) {
            slots.add(value);
        }
        switch (value) {
            case PhpArray a -> a.entries().values().forEach(v -> number(v, slots));
            case PhpObject o -> o.properties().entries().values().forEach(v -> number(v, slots));
            default -> {}
        }
    }

    /** A reference back into a value that is still being encoded. */
    private static final class Cycle extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** Encodes a parsed value; a reference becomes the value it names, as qp_tree sees PHP's references. */
    private static final class Encoder {

        private final List<PhpValue> slots;
        private final Set<PhpValue> open = Collections.newSetFromMap(new IdentityHashMap<>());

        Encoder(List<PhpValue> slots) {
            this.slots = slots;
        }

        ObjectNode encode(PhpValue value) {
            if (!open.add(value)) {
                throw new Cycle();
            }
            try {
                return switch (value) {
                    case PhpNull n -> PhpTree.nul();
                    case PhpBool b -> PhpTree.bool(b.value());
                    case PhpInt i -> PhpTree.integer(i.value());
                    case PhpFloat f -> {
                        String s = PhpSerialize.serializeToString(f); // "d:<shortest>;", as qp_float() takes it
                        yield PhpTree.floating(s.substring(2, s.length() - 1));
                    }
                    case PhpString s -> PhpTree.string(s.bytes());
                    case PhpArray a -> PhpTree.array(entries(a));
                    case PhpObject o -> PhpTree.object(o.className(), entries(o.properties()));
                    case PhpCustomObject c -> PhpTree.custom(c.className(), c.payload());
                    case PhpEnum e -> PhpTree.enumCase(e.className(), e.caseName());
                    case PhpReference r -> {
                        if (r.index() < 1 || r.index() > slots.size()) {
                            throw new IllegalArgumentException("reference " + r + " names no value");
                        }
                        yield encode(slots.get((int) r.index() - 1));
                    }
                };
            } finally {
                open.remove(value);
            }
        }

        private List<PhpTree.Entry> entries(PhpArray array) {
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
    }
}

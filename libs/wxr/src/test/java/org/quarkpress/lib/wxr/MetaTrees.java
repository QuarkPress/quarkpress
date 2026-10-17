package org.quarkpress.lib.wxr;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            case MaybeUnserializeResult.Parsed p -> encode(p.value());
            case MaybeUnserializeResult.Failed f -> PhpTree.bool(false);
        };
    }

    private static ObjectNode encode(PhpValue value) {
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
            case PhpReference r -> throw new IllegalArgumentException("meta parity values contain no references");
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
}

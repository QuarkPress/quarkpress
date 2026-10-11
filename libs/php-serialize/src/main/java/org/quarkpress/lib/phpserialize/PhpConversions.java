package org.quarkpress.lib.phpserialize;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts {@link PhpValue} trees to plain Java objects. */
final class PhpConversions {

    private PhpConversions() {}

    static Object toJavaObject(PhpValue value) {
        return switch (value) {
            case PhpNull n -> null;
            case PhpBool b -> b.value();
            case PhpInt i -> i.value();
            case PhpFloat f -> f.value();
            case PhpString s -> s.text();
            case PhpArray a -> a.isList() ? toList(a) : toMap(a, false);
            case PhpObject o -> toMap(o.properties(), true);
            case PhpCustomObject c -> new String(c.payload(), StandardCharsets.UTF_8);
            case PhpReference r -> null;
            case PhpEnum e -> e.className() + ":" + e.caseName();
        };
    }

    private static List<Object> toList(PhpArray array) {
        List<Object> list = new ArrayList<>(array.size());
        for (PhpValue v : array.entries().values()) {
            list.add(toJavaObject(v));
        }
        return list;
    }

    private static Map<String, Object> toMap(PhpArray array, boolean unmangle) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<PhpArray.Key, PhpValue> e : array.entries().entrySet()) {
            String key = keyToString(e.getKey());
            map.put(unmangle ? unmanglePropertyName(key) : key, toJavaObject(e.getValue()));
        }
        return map;
    }

    static String keyToString(PhpArray.Key key) {
        return switch (key) {
            case PhpArray.Key.Int i -> Long.toString(i.value());
            case PhpArray.Key.Str s -> s.value().text();
        };
    }

    /**
     * PHP mangles non-public property names as {@code \0Class\0name} (private) or {@code \0*\0name}
     * (protected). Returns the visible name.
     */
    static String unmanglePropertyName(String name) {
        if (name.length() > 2 && name.charAt(0) == '\0') {
            int second = name.indexOf('\0', 1);
            if (second > 0 && second < name.length() - 1) {
                return name.substring(second + 1);
            }
        }
        return name;
    }
}

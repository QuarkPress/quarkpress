package org.quarkpress.lib.phpserialize;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts {@link PhpValue} trees to plain Java objects. */
final class PhpConversions {

    private PhpConversions() {}

    /** Resolves back-references first ({@link PhpReferences}), then converts. */
    static Object toJavaObject(PhpValue value) {
        return convert(PhpReferences.resolve(value));
    }

    private static Object convert(PhpValue value) {
        return switch (value) {
            case PhpNull n -> null;
            case PhpBool b -> b.value();
            case PhpInt i -> i.value();
            case PhpFloat f -> f.value();
            case PhpString s -> s.text();
            case PhpArray a -> a.isList() ? toList(a) : toMap(a);
            case PhpObject o -> toPropertyMap(o.properties());
            case PhpCustomObject c -> new String(c.payload(), StandardCharsets.UTF_8);
            case PhpReference r -> null; // resolve() leaves none behind
            case PhpEnum e -> e.className() + ":" + e.caseName();
        };
    }

    private static List<Object> toList(PhpArray array) {
        List<Object> list = new ArrayList<>(array.size());
        for (PhpValue v : array.entries().values()) {
            list.add(convert(v));
        }
        return list;
    }

    private static Map<String, Object> toMap(PhpArray array) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<PhpArray.Key, PhpValue> e : array.entries().entrySet()) {
            map.put(keyToString(e.getKey()), convert(e.getValue()));
        }
        return map;
    }

    private static Map<String, Object> toPropertyMap(PhpArray properties) {
        Map<String, Object> map = new LinkedHashMap<>();
        visibleProperties(properties).forEach((name, value) -> map.put(name, convert(value)));
        return map;
    }

    /**
     * Object properties by visible name, without the private ({@code \0Class\0name}) and protected ({@code
     * \0*\0name}) prefixes. When visible names collide, a public property wins over non-public ones and the first
     * non-public one wins over later ones; the entry keeps the position of the name's first occurrence.
     */
    static Map<String, PhpValue> visibleProperties(PhpArray properties) {
        Map<String, PhpValue> visible = new LinkedHashMap<>();
        Set<String> fromPublic = new HashSet<>();
        for (Map.Entry<PhpArray.Key, PhpValue> e : properties.entries().entrySet()) {
            String raw = keyToString(e.getKey());
            String name = unmanglePropertyName(raw);
            if (name.equals(raw)) {
                visible.put(name, e.getValue());
                fromPublic.add(name);
            } else if (!fromPublic.contains(name)) {
                visible.putIfAbsent(name, e.getValue());
            }
        }
        return visible;
    }

    static String keyToString(PhpArray.Key key) {
        return switch (key) {
            case PhpArray.Key.Int i -> Long.toString(i.value());
            case PhpArray.Key.Str s -> s.value().text();
        };
    }

    /**
     * PHP mangles non-public property names as {@code \0Class\0name} (private) or {@code \0*\0name} (protected).
     * Returns the visible name.
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

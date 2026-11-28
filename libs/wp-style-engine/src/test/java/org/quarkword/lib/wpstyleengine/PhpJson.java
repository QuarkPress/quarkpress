package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Java results in the shape PHP's {@code json_encode} gives the same values. */
final class PhpJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private PhpJson() {}

    /** An empty map is {@code []}; keys exactly {@code "0"..."n-1"} in order are a list; anything else an object. */
    static JsonNode map(Map<String, ? extends Object> m) {
        boolean list = true;
        int i = 0;
        for (String key : m.keySet()) {
            if (!key.equals(Integer.toString(i++))) {
                list = false;
                break;
            }
        }
        if (list) {
            return list(new ArrayList<>(m.values()));
        }
        ObjectNode object = NODES.objectNode();
        m.forEach((k, v) -> object.set(k, value(v)));
        return object;
    }

    static JsonNode list(List<?> l) {
        ArrayNode array = NODES.arrayNode();
        l.forEach(v -> array.add(value(v)));
        return array;
    }

    private static JsonNode value(Object v) {
        return switch (v) {
            case null -> NODES.nullNode();
            case JsonNode node -> node;
            case String s -> NODES.textNode(s);
            case Boolean b -> NODES.booleanNode(b);
            case Map<?, ?> m -> {
                @SuppressWarnings("unchecked")
                Map<String, ?> typed = (Map<String, ?>) m;
                yield map(typed);
            }
            case List<?> l -> list(l);
            default -> throw new IllegalArgumentException("no PHP JSON shape for " + v.getClass());
        };
    }
}

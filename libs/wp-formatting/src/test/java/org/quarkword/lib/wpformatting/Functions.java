package org.quarkword.lib.wpformatting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** QuarkWord's answer for a case, in the JSON shape the php driver returns for the same {@code fn}. */
final class Functions {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Functions() {}

    static JsonNode ours(String fn, String in, JsonNode args) {
        return switch (fn) {
            // Task 1
            case "html_split" -> texts(Formatting.htmlSplit(in));
            case "replace_in_html_tags" -> NODES.textNode(Formatting.replaceInHtmlTags(in, pairs(args.get("pairs"))));
            default -> throw new IllegalArgumentException("unknown fn " + fn);
        };
    }

    /** Every {@code fn} that {@link #ours} dispatches; each task adds its own. */
    static final Set<String> FNS = Set.of(
            // Task 1
            "html_split", "replace_in_html_tags");

    static Map<String, String> pairs(JsonNode object) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            pairs.put(e.getKey(), e.getValue().asText());
        }
        return pairs;
    }

    private static ArrayNode texts(Iterable<String> items) {
        ArrayNode array = NODES.arrayNode();
        items.forEach(array::add);
        return array;
    }
}

package org.quarkword.lib.wpformatting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
            // Task 2
            case "autop" -> NODES.textNode(Formatting.autop(in, args.path("br").asBoolean(true)));
            case "autop_newline_helper" -> NODES.textNode(Autop.preserveNewlines(in));
            // Task 3
            case "texturize" -> NODES.textNode(texturize(args).apply(in));
            default -> throw new IllegalArgumentException("unknown fn " + fn);
        };
    }

    /** Every {@code fn} that {@link #ours} dispatches; each task adds its own. */
    static final Set<String> FNS = Set.of(
            // Task 1
            "html_split",
            "replace_in_html_tags",
            // Task 2
            "autop",
            "autop_newline_helper",
            // Task 3
            "texturize");

    /** The shortcodes WordPress core registers; a texturize case gives its own list in {@code shortcodes}. */
    static final List<String> CORE_SHORTCODES =
            List.of("wp_caption", "caption", "gallery", "playlist", "audio", "video", "embed");

    /**
     * The texturizer a texturize case describes, as the driver sets WordPress up: {@code shortcodes} (the registry,
     * default {@link #CORE_SHORTCODES}), {@code cockney} (the whole cockney list), {@code noTexturizeTags} and
     * {@code noTexturizeShortcodes} (each replaces its list).
     */
    static Texturize texturize(JsonNode args) {
        Texturize.Builder builder = Texturize.builder()
                .registeredShortcodes(args.has("shortcodes") ? strings(args.get("shortcodes")) : CORE_SHORTCODES);
        if (args.has("cockney")) {
            builder.strings(TexturizeStrings.english().withCockney(pairs(args.get("cockney"))));
        }
        if (args.has("noTexturizeTags")) {
            builder.noTexturizeTags(strings(args.get("noTexturizeTags")));
        }
        if (args.has("noTexturizeShortcodes")) {
            builder.noTexturizeShortcodes(strings(args.get("noTexturizeShortcodes")));
        }
        return builder.build();
    }

    static List<String> strings(JsonNode array) {
        List<String> items = new ArrayList<>();
        array.forEach(item -> items.add(item.asText()));
        return items;
    }

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

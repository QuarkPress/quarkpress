package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import java.util.Map;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * What the post types route shows of a type beyond its registration: WordPress's menu icon, block template, template
 * lock and REST namespace, and the arguments a supported feature was registered with. The four built-in types have
 * the values WordPress gives them; any other type has WordPress's defaults (no icon, an empty template, no lock, the
 * {@code wp/v2} namespace, every feature supported without arguments).
 *
 * @param icon the menu icon, or null
 * @param template the block template (a list)
 * @param templateLock the template lock: a text, or {@code false}
 * @param restNamespace the namespace of the type's REST routes
 * @param supportArguments per supported feature that has them, its arguments as WordPress lists them
 */
public record TypeExtras(
        String icon,
        JsonNode template,
        JsonNode templateLock,
        String restNamespace,
        Map<String, JsonNode> supportArguments) {

    private static final String NAMESPACE = "wp/v2";

    /** A fresh copy of the values for {@code type}: changing one does not change what later calls give. */
    public static TypeExtras of(String type) {
        return switch (type) {
            case "post" -> new TypeExtras("dashicons-admin-post", list(), BooleanNode.FALSE, NAMESPACE, notes());
            case "page" -> new TypeExtras("dashicons-admin-page", list(), BooleanNode.FALSE, NAMESPACE, notes());
            case "attachment" ->
                new TypeExtras("dashicons-admin-media", list(), BooleanNode.FALSE, NAMESPACE, Map.of());
            default -> new TypeExtras(null, list(), BooleanNode.FALSE, NAMESPACE, Map.of());
        };
    }

    private static ArrayNode list() {
        return RestJson.NODES.arrayNode();
    }

    /** The editor feature with notes, which WordPress adds to posts and pages. */
    private static Map<String, JsonNode> notes() {
        ArrayNode arguments = list();
        arguments.addObject().put("notes", true);
        return Map.of("editor", arguments);
    }
}

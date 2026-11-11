package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.blocks.BlockType;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.PhpObjectNode;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * A block type's record as WordPress's block types routes answer it: {@code attributes}, {@code is_dynamic}, then the
 * type's properties in WordPress's order (each the type's value, or the schema's default when the type has none), then
 * the deprecated singular script and style fields (the first handle of each list, or null). Objects decoded from a
 * {@code block.json} file are PHP arrays in WordPress, so an empty one prints as {@code []}, wherever it sits.
 */
final class BlockTypeFields {

    private static final String API = "https://api.w.org/";

    private BlockTypeFields() {}

    /** The fields of {@code type} the selection includes, in WordPress's order. */
    static ObjectNode of(BlockType type, ObjectNode attributes, boolean dynamic, Fields.Selection selection) {
        ObjectNode data = RestJson.NODES.objectNode();
        if (selection.includes("attributes")) {
            data.set("attributes", phpArrays(attributes));
        }
        if (selection.includes("is_dynamic")) {
            data.put("is_dynamic", dynamic);
        }
        if (selection.includes("api_version")) {
            data.put("api_version", type.apiVersion());
        }
        if (selection.includes("name")) {
            data.put("name", type.name());
        }
        if (selection.includes("title")) {
            data.put("title", type.title());
        }
        if (selection.includes("description")) {
            data.put("description", type.description());
        }
        if (selection.includes("icon")) {
            data.set("icon", type.icon().map(BlockTypeFields::phpArrays).orElse(NullNode.getInstance()));
        }
        if (selection.includes("category")) {
            data.set("category", text(type.category()));
        }
        if (selection.includes("keywords")) {
            data.set("keywords", strings(type.keywords()));
        }
        if (selection.includes("parent")) {
            data.set("parent", type.parent().map(BlockTypeFields::strings).orElse(null));
        }
        if (selection.includes("ancestor")) {
            data.set("ancestor", type.ancestor().map(BlockTypeFields::strings).orElse(null));
        }
        if (selection.includes("allowed_blocks")) {
            data.set(
                    "allowed_blocks",
                    type.allowedBlocks().map(BlockTypeFields::strings).orElse(null));
        }
        if (selection.includes("provides_context")) {
            data.set("provides_context", phpArrays(type.providesContext()));
        }
        if (selection.includes("uses_context")) {
            data.set("uses_context", strings(type.usesContext()));
        }
        if (selection.includes("selectors")) {
            data.set("selectors", phpArrays(type.selectors()));
        }
        if (selection.includes("supports")) {
            data.set("supports", phpArrays(type.supports()));
        }
        if (selection.includes("styles")) {
            data.set("styles", styles(type.styles()));
        }
        if (selection.includes("textdomain")) {
            data.set("textdomain", text(type.textdomain()));
        }
        if (selection.includes("example")) {
            data.set("example", type.example().map(BlockTypeFields::phpArrays).orElse(NullNode.getInstance()));
        }
        handles(data, selection, "editor_script_handles", type.editorScriptHandles());
        handles(data, selection, "script_handles", type.scriptHandles());
        handles(data, selection, "view_script_handles", type.viewScriptHandles());
        handles(data, selection, "view_script_module_ids", type.viewScriptModuleIds());
        handles(data, selection, "editor_style_handles", type.editorStyleHandles());
        handles(data, selection, "style_handles", type.styleHandles());
        handles(data, selection, "view_style_handles", type.viewStyleHandles());
        if (selection.includes("variations")) {
            data.set("variations", phpArrays(type.variations()));
        }
        if (selection.includes("block_hooks")) {
            data.set("block_hooks", phpArrays(type.blockHooks()));
        }
        first(data, selection, "editor_script", type.editorScriptHandles());
        first(data, selection, "script", type.scriptHandles());
        first(data, selection, "view_script", type.viewScriptHandles());
        first(data, selection, "editor_style", type.editorStyleHandles());
        first(data, selection, "style", type.styleHandles());
        return data;
    }

    /**
     * The links of {@code type}: its collection, itself, its namespace's collection and, for a dynamic type, the
     * renderer in the edit context.
     */
    static Links links(BlockType type, boolean dynamic, RestUrls urls) {
        String name = type.name();
        String namespace = name.substring(0, name.indexOf('/'));
        Links links = Links.none()
                .add("collection", urls.route(BlockTypesController.BASE))
                .add("self", urls.route(BlockTypesController.BASE + "/" + name))
                .add("up", urls.route(BlockTypesController.BASE + "/" + namespace));
        if (dynamic) {
            links = links.add(
                    API + "render-block", urls.route("/wp/v2/block-renderer/" + name, Map.of("context", "edit")));
        }
        return links;
    }

    private static void handles(ObjectNode data, Fields.Selection selection, String field, List<String> handles) {
        if (selection.includes(field)) {
            data.set(field, strings(handles));
        }
    }

    /** A deprecated singular field: the list's first entry, or null when it is empty. */
    private static void first(ObjectNode data, Fields.Selection selection, String field, List<String> handles) {
        if (selection.includes(field)) {
            data.set(field, handles.isEmpty() ? NullNode.getInstance() : TextNode.valueOf(handles.get(0)));
        }
    }

    /** The type's styles without the empty entries (null, false, zero, empty text or an empty object or list). */
    private static ArrayNode styles(ArrayNode styles) {
        ArrayNode kept = RestJson.NODES.arrayNode();
        for (JsonNode style : styles) {
            if (!phpEmpty(style)) {
                kept.add(phpArrays(style));
            }
        }
        return kept;
    }

    private static boolean phpEmpty(JsonNode value) {
        if (value.isNull()) {
            return true;
        }
        if (value.isContainerNode()) {
            return value.isEmpty();
        }
        if (value.isTextual()) {
            return value.textValue().isEmpty() || value.textValue().equals("0");
        }
        if (value.isBoolean()) {
            return !value.booleanValue();
        }
        return value.isNumber() && value.doubleValue() == 0.0;
    }

    private static JsonNode text(Optional<String> value) {
        return value.<JsonNode>map(TextNode::valueOf).orElse(NullNode.getInstance());
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode list = RestJson.NODES.arrayNode();
        values.forEach(list::add);
        return list;
    }

    /**
     * {@code value} as WordPress prints the PHP array it holds: every empty object (not a PHP object) as {@code []}.
     */
    static JsonNode phpArrays(JsonNode value) {
        if (value instanceof ObjectNode object && !(value instanceof PhpObjectNode)) {
            if (object.isEmpty()) {
                return RestJson.NODES.arrayNode();
            }
            ObjectNode copy = RestJson.NODES.objectNode();
            object.properties().forEach(entry -> copy.set(entry.getKey(), phpArrays(entry.getValue())));
            return copy;
        }
        if (value instanceof ArrayNode array) {
            ArrayNode copy = RestJson.NODES.arrayNode();
            array.forEach(item -> copy.add(phpArrays(item)));
            return copy;
        }
        return value.deepCopy();
    }
}

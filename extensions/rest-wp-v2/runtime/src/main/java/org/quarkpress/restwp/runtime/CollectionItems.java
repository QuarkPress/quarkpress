package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;

/** The items of a collection as WordPress's controllers add them to the list they answer. */
public final class CollectionItems {

    private CollectionItems() {}

    /**
     * WordPress's {@code prepare_response_for_collection}: the item's data with its rendered {@code _links} inlined
     * (when it has links), so that the server's {@code _fields} reduction and embedding see them as part of the item.
     * The item's own body is not changed.
     */
    public static JsonNode inline(RestResponse item, RestRequest request) {
        JsonNode data = asArray(item.body());
        if (item.links().isEmpty()) {
            return data;
        }
        return withLinks(data, LinkRenderer.render(item.links(), request));
    }

    /**
     * {@code data} with {@code links} set as its {@code _links} entry, as PHP sets an array key: an object keeps its
     * entries (a {@code _links} entry it has is replaced in place, any other is added last), a list becomes an object
     * keyed by its indexes, null becomes an object; any other value, which PHP cannot index, is kept as it is. Nothing
     * is set when {@code links} is empty. {@code data} itself is not changed.
     */
    static JsonNode withLinks(JsonNode data, ObjectNode links) {
        if (links.isEmpty()) {
            return data;
        }
        ObjectNode object;
        if (data instanceof ObjectNode existing) {
            object = RestJson.NODES.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> fields = existing.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                object.set(field.getKey(), field.getValue());
            }
        } else if (data instanceof ArrayNode list) {
            object = RestJson.NODES.objectNode();
            for (int i = 0; i < list.size(); i++) {
                object.set(Integer.toString(i), list.get(i));
            }
        } else if (data.isNull() || data.isMissingNode()) {
            object = RestJson.NODES.objectNode();
        } else {
            return data;
        }
        object.set("_links", links);
        return object;
    }

    /** PHP's {@code (array)} of the item's data: null is an empty list, a scalar a list of itself. */
    private static JsonNode asArray(JsonNode data) {
        if (data.isContainerNode()) {
            return data;
        }
        ArrayNode list = RestJson.NODES.arrayNode();
        if (!data.isNull() && !data.isMissingNode()) {
            list.add(data);
        }
        return list;
    }
}

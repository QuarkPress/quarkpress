package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.quarkpress.restwp.PhpObjectNode;

/**
 * WordPress's filtering of an answer by its context. An object's entry is checked against its property's schema, or
 * else the schema's {@code additionalProperties} (when that is a schema); a list's items against the {@code items}
 * schema. An entry whose schema has no {@code context} list is kept as it is; one whose list lacks the context is
 * removed, and for a list all items share the schema, so the whole list becomes {@code []}; otherwise a nested
 * object or list is filtered by that schema. A value is an object or a list as the schema's {@code type} says
 * ({@code properties} without a type means an object; a type naming both goes by the value: a list, or an object
 * keyed by integers, is a list). What lost entries prints as PHP prints the array left: {@code []} when empty, a
 * list when its keys are 0, 1, 2 and so on, else an object keeping the keys (a list's indexes included). A PHP object
 * ({@link PhpObjectNode}) is filtered the same way but stays an object, and both-type schemas take it as an object.
 * Other values, and data whose schema names no type, are kept. {@code patternProperties}, {@code anyOf} and
 * {@code oneOf} are not consulted (no schema of this module uses them). The walk recurses only as deep as the schema,
 * and its work is linear in the data.
 */
public final class ContextFilter {

    private ContextFilter() {}

    /** {@code data} without what {@code context} may not show; the input is not changed. */
    public static JsonNode apply(JsonNode data, ObjectNode schema, String context) {
        return apply(data, schema, context, new long[1]);
    }

    /** {@link #apply(JsonNode, ObjectNode, String)}, adding to {@code work[0]} each entry looked at. */
    static JsonNode apply(JsonNode data, JsonNode schema, String context, long[] work) {
        if (!data.isContainerNode() || schema == null || !schema.isObject()) {
            return data;
        }
        JsonNode type = schema.get("type");
        boolean isArray;
        boolean isObject;
        if (type != null && !type.isNull()) {
            isArray = names(type, "array");
            isObject = names(type, "object");
        } else if (schema.get("properties") != null && !schema.get("properties").isNull()) {
            isArray = false;
            isObject = true;
        } else {
            return data;
        }
        boolean phpObject = data instanceof PhpObjectNode;
        if (isArray && isObject) {
            boolean list = data.isArray() || (!phpObject && PhpValues.hasIntegerKeys((ObjectNode) data));
            isArray = list;
            isObject = !list;
        }
        JsonNode additional = isObject ? schema.get("additionalProperties") : null;
        boolean hasAdditional = additional != null && additional.isContainerNode();
        JsonNode properties = schema.get("properties");
        JsonNode items = schema.get("items");
        List<String> keys = new ArrayList<>(data.size());
        List<JsonNode> values = new ArrayList<>(data.size());
        boolean removed = false;
        Iterator<Map.Entry<String, JsonNode>> entries =
                data.isArray() ? null : data.properties().iterator();
        for (int i = 0; i < data.size(); i++) {
            work[0]++;
            String key;
            JsonNode value;
            if (entries == null) {
                key = Integer.toString(i);
                value = data.get(i);
            } else {
                Map.Entry<String, JsonNode> entry = entries.next();
                key = entry.getKey();
                value = entry.getValue();
            }
            JsonNode check = isArray ? items : isObject ? property(properties, key, additional, hasAdditional) : null;
            Verdict verdict = verdict(check, context);
            if (verdict == Verdict.REMOVE) {
                if (isArray) {
                    // every item shares the schema
                    return RestJson.NODES.arrayNode();
                }
                removed = true;
                continue;
            }
            keys.add(key);
            values.add(verdict == Verdict.FILTER ? apply(value, check, context, work) : value);
        }
        return assemble(data, keys, values, removed, phpObject);
    }

    /**
     * The kept entries as PHP prints them: a PHP object stays an object; data nothing was removed from keeps its form;
     * otherwise an emptied array is {@code []}, an array whose keys are 0, 1, 2 and so on in order is a list, and any
     * other array is an object (a list that lost items keeps their indexes as keys).
     */
    private static JsonNode assemble(
            JsonNode data, List<String> keys, List<JsonNode> values, boolean removed, boolean phpObject) {
        boolean asList;
        if (phpObject) {
            asList = false;
        } else if (!removed) {
            asList = data.isArray();
        } else {
            asList = true;
            for (int i = 0; i < keys.size() && asList; i++) {
                asList = keys.get(i).equals(Integer.toString(i));
            }
        }
        if (asList) {
            ArrayNode list = RestJson.NODES.arrayNode(values.size());
            values.forEach(list::add);
            return list;
        }
        ObjectNode object = phpObject ? new PhpObjectNode() : RestJson.NODES.objectNode();
        for (int i = 0; i < keys.size(); i++) {
            object.set(keys.get(i), values.get(i));
        }
        return object;
    }

    private enum Verdict {
        KEEP,
        REMOVE,
        FILTER
    }

    /** What happens to a value checked against {@code check}. */
    private static Verdict verdict(JsonNode check, String context) {
        JsonNode contexts = check == null || !check.isObject() ? null : check.get("context");
        if (contexts == null || contexts.isNull()) {
            return Verdict.KEEP;
        }
        boolean listed = false;
        if (contexts.isContainerNode()) {
            for (JsonNode name : contexts) {
                if (name.isTextual() && name.textValue().equals(context)) {
                    listed = true;
                    break;
                }
            }
        }
        return listed ? Verdict.FILTER : Verdict.REMOVE;
    }

    /** The schema of the entry {@code key}: its property's, else {@code additionalProperties} when a schema. */
    private static JsonNode property(JsonNode properties, String key, JsonNode additional, boolean hasAdditional) {
        JsonNode property = properties == null || !properties.isObject() ? null : properties.get(key);
        if (property != null && !property.isNull()) {
            return property;
        }
        return hasAdditional ? additional : null;
    }

    /** Whether {@code type} is {@code name} or a list holding it. */
    private static boolean names(JsonNode type, String name) {
        if (type.isTextual()) {
            return type.textValue().equals(name);
        }
        if (type.isArray()) {
            for (JsonNode listed : type) {
                if (listed.isTextual() && listed.textValue().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}

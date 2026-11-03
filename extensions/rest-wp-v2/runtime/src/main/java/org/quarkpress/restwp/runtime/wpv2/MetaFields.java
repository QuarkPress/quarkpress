package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.RegisteredMeta;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.SchemaSanitizer;
import org.quarkpress.restwp.runtime.SchemaValidator;

/**
 * The {@code meta} field of a post: its schema (one property per registered key shown in REST for the type, under
 * the key's REST name) and its value. Reading needs no check beyond reading the post: a single key reads its first
 * stored value, or its default when none is stored; a multi-valued key reads every stored value. Each value is
 * prepared as WordPress prepares it: an empty text stored for a boolean, integer or number reads as false, 0 or 0.0,
 * a value that does not fit the key's schema reads as null, and any other is coerced to its type.
 */
@ApplicationScoped
class MetaFields {

    private static final List<String> VIEW_EDIT = List.of("view", "edit");

    @Inject
    RegisteredMeta registeredMeta;

    /** The {@code meta} property: an object, in the view and edit contexts, with the type's REST keys. */
    ObjectNode schema(String type) {
        ObjectNode meta = RestJson.NODES.objectNode();
        meta.put("description", "Meta fields.");
        meta.put("type", "object");
        ArrayNode contexts = meta.putArray("context");
        VIEW_EDIT.forEach(contexts::add);
        ObjectNode properties = meta.putObject("properties");
        for (MetaKeyDefinition key : registeredMeta.restKeys(type)) {
            properties.set(key.restKey(), keySchema(key));
        }
        return meta;
    }

    /**
     * The REST schema of a key: type, title (its label), description and default, then what the registration
     * overlays; an object without {@code additionalProperties} refuses other properties, at every depth; a key that
     * is not single is a list of such values.
     */
    static ObjectNode keySchema(MetaKeyDefinition key) {
        ObjectNode registered = key.schema();
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("type", key.type());
        schema.set("title", textOrEmpty(registered.get("title")));
        schema.set("description", textOrEmpty(registered.get("description")));
        schema.set("default", key.emptyValue());
        for (Iterator<Map.Entry<String, JsonNode>> it = registered.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (!schema.has(entry.getKey())) {
                schema.set(entry.getKey(), entry.getValue());
            }
        }
        closeObjects(schema);
        if (key.single()) {
            return schema;
        }
        ObjectNode list = RestJson.NODES.objectNode();
        list.put("type", "array");
        list.set("items", schema);
        return list;
    }

    /** The post's {@code meta}: each REST key's prepared value, or an empty list when the type has no key. */
    JsonNode read(String type, PostSnapshot post) {
        List<MetaKeyDefinition> keys = registeredMeta.restKeys(type);
        if (keys.isEmpty()) {
            return RestJson.NODES.arrayNode();
        }
        ObjectNode meta = RestJson.NODES.objectNode();
        for (MetaKeyDefinition key : keys) {
            List<JsonNode> stored = post.meta(key.key());
            ObjectNode schema = keySchema(key);
            if (key.single()) {
                JsonNode value = stored.isEmpty() ? key.emptyValue() : stored.get(0);
                meta.set(key.restKey(), prepare(value, schema, "meta." + key.restKey()));
            } else {
                ArrayNode values = RestJson.NODES.arrayNode();
                ObjectNode items = (ObjectNode) schema.get("items");
                if (stored.isEmpty() && key.schema().has("default")) {
                    values.add(prepare(key.emptyValue(), items, "meta." + key.restKey()));
                }
                for (JsonNode value : stored) {
                    values.add(prepare(value, items, "meta." + key.restKey()));
                }
                meta.set(key.restKey(), values);
            }
        }
        return meta;
    }

    /** WordPress's preparation of a stored value: empty texts of number-like types, the check, then the coercion. */
    private static JsonNode prepare(JsonNode value, ObjectNode schema, String param) {
        JsonNode checked = value;
        if (value.isTextual() && value.textValue().isEmpty()) {
            checked = switch (schema.path("type").asText()) {
                case "boolean" -> BooleanNode.FALSE;
                case "integer" -> IntNode.valueOf(0);
                case "number" -> DoubleNode.valueOf(0.0);
                default -> value;
            };
        }
        if (SchemaValidator.validate(checked, schema, param).isPresent()) {
            return NullNode.getInstance();
        }
        return SchemaSanitizer.sanitize(checked, schema, param);
    }

    /** Sets {@code additionalProperties: false} on every object schema that declares none, at every depth. */
    private static void closeObjects(ObjectNode schema) {
        if (namesObject(schema.get("type")) && !schema.has("additionalProperties")) {
            schema.put("additionalProperties", false);
        }
        if (schema.get("properties") instanceof ObjectNode properties) {
            for (JsonNode property : properties) {
                if (property instanceof ObjectNode nested) {
                    closeObjects(nested);
                }
            }
        }
        if (schema.get("items") instanceof ObjectNode items) {
            closeObjects(items);
        }
        if (schema.get("additionalProperties") instanceof ObjectNode additional) {
            closeObjects(additional);
        }
    }

    /** Whether a schema's {@code type} is {@code object} or a list naming it. */
    private static boolean namesObject(JsonNode type) {
        if (type == null) {
            return false;
        }
        if (type.isTextual()) {
            return type.textValue().equals("object");
        }
        if (type.isArray()) {
            for (JsonNode name : type) {
                if (name.isTextual() && name.textValue().equals("object")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonNode textOrEmpty(JsonNode value) {
        return value != null && value.isTextual() ? value : RestJson.NODES.textNode("");
    }
}

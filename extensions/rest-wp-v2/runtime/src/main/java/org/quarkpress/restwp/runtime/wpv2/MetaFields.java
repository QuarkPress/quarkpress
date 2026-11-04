package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.quarkpress.core.PostId;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.persistence.runtime.JsonValues;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.MetaWrite;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.RegisteredMeta;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.SchemaSanitizer;
import org.quarkpress.restwp.runtime.SchemaValidator;

/**
 * The {@code meta} field of a post: its schema (one property per registered key shown in REST for the type, under
 * the key's REST name) and its value. Reading needs no check beyond reading the post: a single key reads its first
 * stored value, or its default when none is stored; a multi-valued key reads every stored value. Each value is
 * prepared as WordPress prepares it: an empty text stored for a boolean, integer or number reads as false, 0 or 0.0,
 * a value that does not fit the key's schema reads as null, and any other is coerced to its type.
 *
 * <p>Writing takes the registered keys the request names, in registration order (other names are ignored): null, or
 * an empty list for a key of several values, deletes the key; any other value must fit the key's schema, is coerced
 * to it and stored through the posts module, which runs the key's sanitizer for the caller and checks the caller's
 * {@code edit_post} on the post and the key's own permission (a protected key needs its auth; an unchanged value
 * needs no permission). Every key is tried; the errors are answered together, the first one's code first. A key's
 * default that does not fit its schema fails the start.
 */
@ApplicationScoped
class MetaFields {

    private static final List<String> VIEW_EDIT = List.of("view", "edit");

    @Inject
    RegisteredMeta registeredMeta;

    @Inject
    PostTypes types;

    /**
     * Refuses the start when a registered key's default does not fit the key's schema, as WordPress refuses to
     * register such a key.
     */
    void checkDefaults(@Observes StartupEvent started) {
        Map<String, MetaKeyDefinition> keys = new LinkedHashMap<>();
        registeredMeta.keys("").forEach(key -> keys.putIfAbsent("\u0000" + key.key(), key));
        for (PostTypeDefinition type : types.all()) {
            registeredMeta.keys(type.key()).forEach(key -> keys.putIfAbsent(key.subtype() + "\u0000" + key.key(), key));
        }
        for (MetaKeyDefinition key : keys.values()) {
            JsonNode given = key.schema().get("default");
            if (given != null
                    && SchemaValidator.validate(given, valueSchema(key), "default")
                            .isPresent()) {
                throw new IllegalStateException(
                        "QuarkPress: the default of meta key " + key.key() + " does not match its schema");
            }
        }
    }

    /**
     * The {@code meta} write argument: an object or a list (PHP's array) whose registered keys' values nest at most
     * {@value JsonValues#MAX_DEPTH} arrays or objects (the deepest value the posts module stores), else "Invalid
     * parameter."; it is not sanitized here, each key is checked by {@link #write}. A null is no value, as for any
     * argument.
     */
    static Arg arg(ObjectNode schema) {
        JsonNode properties = schema.path("properties");
        return new Arg(
                "meta",
                schema,
                false,
                Optional.empty(),
                Arg.Phase.VALIDATE,
                Optional.of(UnaryOperator.identity()),
                Optional.of(value -> PhpValues.isPhpArray(value) && storableDepth(value, properties)));
    }

    /** Whether each value of a registered key in {@code meta} nests at most {@value JsonValues#MAX_DEPTH} levels. */
    private static boolean storableDepth(JsonNode meta, JsonNode properties) {
        if (!meta.isObject()) {
            return true;
        }
        for (Map.Entry<String, JsonNode> entry : meta.properties()) {
            if (properties.has(entry.getKey()) && depth(entry.getValue()) > JsonValues.MAX_DEPTH) {
                return false;
            }
        }
        return true;
    }

    /** The number of arrays or objects nested in {@code value} at its deepest, counted without recursion. */
    private static int depth(JsonNode value) {
        int deepest = 0;
        Deque<JsonNode> nodes = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        nodes.push(value);
        levels.push(0);
        while (!nodes.isEmpty()) {
            JsonNode node = nodes.pop();
            int level = levels.pop();
            if (node.isContainerNode()) {
                deepest = Math.max(deepest, level + 1);
                for (JsonNode child : node) {
                    nodes.push(child);
                    levels.push(level + 1);
                }
            }
        }
        return deepest;
    }

    /**
     * Writes the registered keys of the post's type that {@code meta} names, in registration order, as the caller;
     * answers the errors of the keys that failed, the first one's code and data first and the others as additional
     * errors, or nothing when every key was written. A value that is not an object names no key.
     */
    Optional<RestError> write(PostRoutes routes, PostId post, JsonNode meta, RestRequest request) {
        if (meta == null || !meta.isObject()) {
            return Optional.empty();
        }
        List<RestError> errors = new ArrayList<>();
        for (MetaKeyDefinition key : registeredMeta.restKeys(routes.key())) {
            JsonNode value = meta.get(key.restKey());
            if (value != null) {
                writeKey(post, key, value, request).ifPresent(errors::add);
            }
        }
        if (errors.isEmpty()) {
            return Optional.empty();
        }
        RestError merged = errors.get(0);
        for (RestError other : errors.subList(1, errors.size())) {
            merged = merged.withError(other);
        }
        return Optional.of(merged);
    }

    private Optional<RestError> writeKey(PostId post, MetaKeyDefinition key, JsonNode value, RestRequest request) {
        String name = key.restKey();
        String param = "meta." + name;
        ObjectNode schema = keySchema(key);
        if (value.isNull() || (!key.single() && PhpValues.isPhpArray(value) && value.isEmpty())) {
            return delete(post, key, request);
        }
        if (!key.single() && value.isArray()) {
            for (JsonNode entry : value) {
                if (entry.isNull()) {
                    return Optional.of(invalidStored(name));
                }
            }
        }
        Optional<RestError> invalid = SchemaValidator.validate(value, schema, param);
        if (invalid.isPresent()) {
            return Optional.of(invalid.get().withData(status(400)));
        }
        JsonNode sanitized = SchemaSanitizer.sanitize(value, schema, param);
        MetaWrite written;
        if (key.single()) {
            written = registeredMeta.updateSingle(post, key, sanitized);
        } else {
            List<JsonNode> values = new ArrayList<>();
            sanitized.forEach(values::add);
            written = registeredMeta.replace(post, key, values);
        }
        return written == MetaWrite.DENIED
                ? Optional.of(notAllowed("rest_cannot_update", key, request))
                : Optional.empty();
    }

    /**
     * Deletes the key: a single key whose stored value does not fit its schema is refused, else the caller needs the
     * key's delete permission (nothing stored is no change).
     */
    private Optional<RestError> delete(PostId post, MetaKeyDefinition key, RestRequest request) {
        if (key.single()) {
            List<JsonNode> stored = registeredMeta.stored(post, key.key());
            if (!stored.isEmpty()
                    && SchemaValidator.validate(stored.get(0), keySchema(key), "meta." + key.restKey())
                            .isPresent()) {
                return Optional.of(invalidStored(key.restKey()));
            }
        }
        return registeredMeta.delete(post, key) == MetaWrite.DENIED
                ? Optional.of(notAllowed("rest_cannot_delete", key, request))
                : Optional.empty();
    }

    private static RestError invalidStored(String name) {
        return RestError.of(
                "rest_invalid_stored_value",
                "The " + name + " property has an invalid stored value, and cannot be updated to null.",
                500);
    }

    private static RestError notAllowed(String code, MetaKeyDefinition key, RestRequest request) {
        ObjectNode data = RestJson.NODES.objectNode();
        data.put("key", key.restKey());
        data.put("status", request.authCode());
        return new RestError(
                code,
                "Sorry, you are not allowed to edit the " + key.restKey() + " custom field.",
                data,
                List.of(),
                List.of());
    }

    private static ObjectNode status(int status) {
        ObjectNode data = RestJson.NODES.objectNode();
        data.put("status", status);
        return data;
    }

    /** The schema of one value of the key: the key's schema, or its items for a key of several values. */
    private static ObjectNode valueSchema(MetaKeyDefinition key) {
        ObjectNode schema = keySchema(key);
        return key.single() ? schema : (ObjectNode) schema.get("items");
    }

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
        return read(type, post::meta);
    }

    /**
     * The {@code meta} of stored values read by key (a revision's copied meta, say): each REST key of the type
     * prepared from the values {@code stored} answers for it, its default when there are none; an empty list when the
     * type has no key.
     */
    JsonNode read(String type, Function<String, List<JsonNode>> stored) {
        List<MetaKeyDefinition> keys = registeredMeta.restKeys(type);
        if (keys.isEmpty()) {
            return RestJson.NODES.arrayNode();
        }
        ObjectNode meta = RestJson.NODES.objectNode();
        for (MetaKeyDefinition key : keys) {
            List<JsonNode> values = stored.apply(key.key());
            ObjectNode schema = keySchema(key);
            if (key.single()) {
                JsonNode value = values.isEmpty() ? key.emptyValue() : values.get(0);
                meta.set(key.restKey(), prepare(value, schema, "meta." + key.restKey()));
            } else {
                ArrayNode list = RestJson.NODES.arrayNode();
                ObjectNode items = (ObjectNode) schema.get("items");
                if (values.isEmpty() && key.schema().has("default")) {
                    list.add(prepare(key.emptyValue(), items, "meta." + key.restKey()));
                }
                for (JsonNode value : values) {
                    list.add(prepare(value, items, "meta." + key.restKey()));
                }
                meta.set(key.restKey(), list);
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

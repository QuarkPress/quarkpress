package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.restwp.RestError;

/**
 * WordPress's sanitizing of a value by an argument's JSON schema, which converts without checking: integers and
 * numbers as PHP casts them, booleans ("false" and "0" in any case are false, other values by PHP truthiness), strings
 * by PHP's string conversion and then the format's own sanitizing, arrays as re-indexed lists of sanitized items
 * (a scalar is split as a list first), objects with their properties sanitized and, under
 * {@code additionalProperties: false}, unknown properties dropped. A schema listing several types uses the first one
 * the value fits; none gives null. An untyped schema keeps the value, unless its format sanitizes it.
 */
public final class SchemaSanitizer {

    private SchemaSanitizer() {}

    /** {@code value} sanitized by {@code schema}; {@code param} names the value as the checks would. */
    public static JsonNode sanitize(JsonNode value, ObjectNode schema, String param) {
        JsonNode typeNode = schema.get("type");
        if (typeNode != null && typeNode.isArray()) {
            String type = SchemaValidator.bestType(value, SchemaValidator.types(typeNode));
            return type == null ? NullNode.getInstance() : sanitizeAs(value, schema, type);
        }
        if (typeNode != null && typeNode.isTextual()) {
            return sanitizeAs(value, schema, typeNode.textValue());
        }
        return format(value, schema);
    }

    /**
     * WordPress's check after sanitizing: a typed schema with {@code uniqueItems} whose sanitized value is a list
     * holding two identical items ({@code ["1","01"]} as integers) fails with {@code rest_duplicate_items}. Only the
     * value itself is checked: WordPress leaves a nested failure inside the sanitized value instead of reporting it.
     */
    public static Optional<RestError> duplicates(JsonNode sanitized, ObjectNode schema, String param) {
        if (schema.has("type")
                && sanitized.isArray()
                && PhpValues.toBoolean(schema.get("uniqueItems"))
                && !SchemaValidator.unique(PhpValues.values(sanitized))) {
            return Optional.of(ValidationMessages.duplicateItems(param));
        }
        return Optional.empty();
    }

    /** {@code value} sanitized as {@code type}, with the rest of {@code schema}. */
    static JsonNode sanitizeAs(JsonNode value, ObjectNode schema, String type) {
        return switch (type) {
            case "array" -> array(value, schema);
            case "object" -> object(value, schema);
            case "null" -> NullNode.getInstance();
            case "integer" -> integer(PhpValues.toLong(value));
            case "number" -> DoubleNode.valueOf(PhpValues.toDouble(value));
            case "boolean" -> BooleanNode.valueOf(PhpValues.toBoolean(value));
            case "string" -> {
                JsonNode formatted = format(value, schema);
                yield formatted == value ? TextNode.valueOf(PhpValues.toPhpString(value)) : formatted;
            }
            default -> value;
        };
    }

    private static JsonNode integer(long value) {
        return value == (int) value ? IntNode.valueOf((int) value) : LongNode.valueOf(value);
    }

    private static JsonNode array(JsonNode value, ObjectNode schema) {
        ArrayNode list = RestJson.NODES.arrayNode();
        if (value.isNull()) {
            return list;
        }
        List<JsonNode> items = SchemaValidator.listOf(value);
        JsonNode itemSchema = schema.get("items");
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            list.add(itemSchema instanceof ObjectNode itemsSchema ? sanitize(item, itemsSchema, "") : item);
        }
        return list;
    }

    private static JsonNode object(JsonNode value, ObjectNode schema) {
        ObjectNode object = PhpValues.isPhpArray(value) ? asObject(value) : RestJson.NODES.objectNode();
        JsonNode properties = schema.get("properties");
        JsonNode additional = schema.get("additionalProperties");
        ObjectNode result = RestJson.NODES.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode propertySchema = properties instanceof ObjectNode declared ? declared.get(entry.getKey()) : null;
            if (propertySchema instanceof ObjectNode known) {
                result.set(entry.getKey(), sanitize(entry.getValue(), known, ""));
            } else if (additional != null && additional.isBoolean() && !additional.booleanValue()) {
                continue;
            } else if (additional instanceof ObjectNode additionalSchema) {
                result.set(entry.getKey(), sanitize(entry.getValue(), additionalSchema, ""));
            } else {
                result.set(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /** A PHP array as an object keyed as PHP keys it (a JSON array's items by index); a copy for a JSON array. */
    static ObjectNode asObject(JsonNode value) {
        if (value instanceof ObjectNode object) {
            return object;
        }
        ObjectNode object = RestJson.NODES.objectNode();
        if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) {
                object.set(Integer.toString(i), value.get(i));
            }
        }
        return object;
    }

    /**
     * The format's sanitizing of the raw value, as WordPress applies it before any string conversion: an array gives
     * the empty string (with {@code uri} and {@code hex-color} WordPress fails with a 500 instead), null gives the
     * empty string, a scalar is sanitized as its PHP string. Without a sanitizing format the value itself.
     */
    private static JsonNode format(JsonNode value, ObjectNode schema) {
        JsonNode format = schema.get("format");
        if (format == null || !format.isTextual()) {
            return value;
        }
        String name = format.textValue();
        boolean known = switch (name) {
            case "date-time", "email", "ip", "uuid", "text-field", "textarea-field", "hex-color", "uri" -> true;
            default -> false;
        };
        if (!known) {
            return value;
        }
        if (PhpValues.isPhpArray(value)) {
            return TextNode.valueOf("");
        }
        String text = PhpValues.toPhpString(value);
        return TextNode.valueOf(
                switch (name) {
                    case "textarea-field" -> TextFields.textareaField(text);
                    case "hex-color" -> Formats.hexColor(text) ? text : "";
                    case "uri" -> TextFields.url(text);
                    default -> TextFields.textField(text);
                });
    }
}

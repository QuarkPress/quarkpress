package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ContainerNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.restwp.RestError;

/**
 * WordPress's check of a value against an argument's JSON schema. The type comes first (with its own constraints:
 * for numbers {@code multipleOf} then the bounds, which for integers come before the whole-number check; for strings
 * the lengths; for arrays the items, then the counts, then uniqueness; for objects the required properties, then each
 * property in the value's order, then the counts), then {@code enum} on the value sanitized for the type, then
 * {@code format} (for strings and untyped schemas). The check stops at the first failure inside a value. A schema
 * listing several types uses the first one the value fits.
 *
 * <p>The {@code pattern} keyword is refused when a schema is registered: WordPress applies it as a regular expression
 * to request input, which this module does not run. So are {@code patternProperties}, {@code anyOf} and
 * {@code oneOf}, which this module does not implement.
 */
public final class SchemaValidator {

    private static final List<String> UNSUPPORTED = List.of("pattern", "patternProperties", "anyOf", "oneOf");

    private SchemaValidator() {}

    /**
     * Throws {@code IllegalArgumentException("unsupported schema keyword: <keyword>")} when the schema, its
     * {@code items}, its {@code properties} or its {@code additionalProperties} (at any depth) use a keyword this
     * module does not check.
     */
    public static void requireSupported(ObjectNode schema) {
        for (String keyword : UNSUPPORTED) {
            if (schema.has(keyword)) {
                throw new IllegalArgumentException("unsupported schema keyword: " + keyword);
            }
        }
        if (schema.get("items") instanceof ObjectNode items) {
            requireSupported(items);
        }
        if (schema.get("properties") instanceof ObjectNode properties) {
            for (JsonNode property : properties) {
                if (property instanceof ObjectNode propertySchema) {
                    requireSupported(propertySchema);
                }
            }
        }
        if (schema.get("additionalProperties") instanceof ObjectNode additional) {
            requireSupported(additional);
        }
    }

    /** The first failure of {@code value} against {@code schema}, named after {@code param}; empty when valid. */
    public static Optional<RestError> validate(JsonNode value, ObjectNode schema, String param) {
        return Optional.ofNullable(check(value, schema, param));
    }

    private static RestError check(JsonNode value, ObjectNode schema, String param) {
        JsonNode typeNode = schema.get("type");
        String type = null;
        if (typeNode != null && typeNode.isArray()) {
            List<String> types = types(typeNode);
            type = bestType(value, types);
            if (type == null) {
                return ValidationMessages.invalidType(param, String.join(",", types));
            }
        } else if (typeNode != null && typeNode.isTextual()) {
            type = typeNode.textValue();
        }
        RestError error = type == null ? null : checkType(value, schema, type, param);
        if (error != null) {
            return error;
        }
        // an enum WordPress declares as an associative array lists its values
        if (schema.get("enum") instanceof ContainerNode<?> values) {
            error = checkEnum(value, schema, type, values, param);
            if (error != null) {
                return error;
            }
        }
        if (schema.get("format") instanceof JsonNode format
                && format.isTextual()
                && (type == null || type.equals("string"))) {
            return checkFormat(value, format.textValue(), param);
        }
        return null;
    }

    static List<String> types(JsonNode typeNode) {
        List<String> types = new ArrayList<>();
        for (JsonNode entry : typeNode) {
            types.add(entry.asText());
        }
        return types;
    }

    /** The first listed type the value fits; the empty string prefers {@code string} when it is listed. */
    static String bestType(JsonNode value, List<String> types) {
        if (value.isTextual() && value.textValue().isEmpty() && types.contains("string")) {
            return "string";
        }
        for (String type : types) {
            if (fits(value, type)) {
                return type;
            }
        }
        return null;
    }

    private static boolean fits(JsonNode value, String type) {
        return switch (type) {
            case "array" -> isArray(value);
            case "object" -> isObject(value);
            case "integer" -> isInteger(value);
            case "number" -> PhpValues.isNumeric(value);
            case "boolean" -> isBoolean(value);
            case "string" -> value.isTextual();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static RestError checkType(JsonNode value, ObjectNode schema, String type, String param) {
        return switch (type) {
            case "array" -> checkArray(value, schema, param);
            case "object" -> checkObject(value, schema, param);
            case "null" -> value.isNull() ? null : ValidationMessages.invalidType(param, "null");
            case "boolean" -> isBoolean(value) ? null : ValidationMessages.invalidType(param, "boolean");
            case "integer" -> checkInteger(value, schema, param);
            case "number" ->
                PhpValues.isNumeric(value)
                        ? checkNumber(value, schema, param)
                        : ValidationMessages.invalidType(param, "number");
            case "string" ->
                value.isTextual()
                        ? checkString(value.textValue(), schema, param)
                        : ValidationMessages.invalidType(param, "string");
            default -> null;
        };
    }

    /** A PHP list: a JSON array, an object with integer keys, or a scalar (split as a list); never null. */
    static boolean isArray(JsonNode value) {
        if (value.isArray()) {
            return true;
        }
        if (value instanceof ObjectNode object) {
            return PhpValues.hasIntegerKeys(object);
        }
        return value.isValueNode() && !value.isNull();
    }

    /** The list a value stands for: its items in order, or a scalar's pieces. */
    static List<JsonNode> listOf(JsonNode value) {
        if (PhpValues.isPhpArray(value)) {
            return PhpValues.values(value);
        }
        List<JsonNode> items = new ArrayList<>();
        for (String piece : PhpValues.parseList(PhpValues.toPhpString(value))) {
            items.add(RestJson.NODES.textNode(piece));
        }
        return items;
    }

    /** An object: a JSON object or array, or the empty string. */
    static boolean isObject(JsonNode value) {
        return PhpValues.isPhpArray(value)
                || (value.isTextual() && value.textValue().isEmpty());
    }

    static boolean isBoolean(JsonNode value) {
        if (value.isBoolean()) {
            return true;
        }
        if (value.isIntegralNumber()) {
            return value.canConvertToLong() && (value.longValue() == 0 || value.longValue() == 1);
        }
        if (value.isTextual()) {
            String text = value.textValue();
            return text.equals("0")
                    || text.equals("1")
                    || text.equalsIgnoreCase("true")
                    || text.equalsIgnoreCase("false");
        }
        return false;
    }

    /** Numeric, and its float value has no fraction (an infinite value counts). */
    static boolean isInteger(JsonNode value) {
        if (!PhpValues.isNumeric(value)) {
            return false;
        }
        if (value.isIntegralNumber()) {
            return true;
        }
        double number = PhpValues.toDouble(value);
        return Math.rint(number) == number;
    }

    /**
     * Numeric first, then the number constraints, then a whole value: "0.5" against a minimum of 1 is out of bounds.
     */
    private static RestError checkInteger(JsonNode value, ObjectNode schema, String param) {
        if (!PhpValues.isNumeric(value)) {
            return ValidationMessages.invalidType(param, "integer");
        }
        RestError error = checkNumber(value, schema, param);
        if (error != null) {
            return error;
        }
        return isInteger(value) ? null : ValidationMessages.invalidType(param, "integer");
    }

    private static RestError checkNumber(JsonNode value, ObjectNode schema, String param) {
        double number = PhpValues.toDouble(value);
        JsonNode multipleOf = schema.get("multipleOf");
        if (multipleOf != null && multipleOf.isNumber()) {
            if (number % multipleOf.doubleValue() != 0.0) {
                return ValidationMessages.notMultiple(param, multipleOf);
            }
        }
        JsonNode minimum = number(schema.get("minimum"));
        JsonNode maximum = number(schema.get("maximum"));
        if (minimum == null && maximum == null) {
            return null;
        }
        boolean exclusiveMinimum = PhpValues.toBoolean(schema.get("exclusiveMinimum"));
        boolean exclusiveMaximum = PhpValues.toBoolean(schema.get("exclusiveMaximum"));
        boolean belowMinimum = minimum != null
                && (exclusiveMinimum ? number <= minimum.doubleValue() : number < minimum.doubleValue());
        boolean aboveMaximum = maximum != null
                && (exclusiveMaximum ? number >= maximum.doubleValue() : number > maximum.doubleValue());
        if (!belowMinimum && !aboveMaximum) {
            return null;
        }
        if (minimum != null && maximum != null) {
            return ValidationMessages.outOfBounds(param + " must be between " + bound(minimum) + " ("
                    + (exclusiveMinimum ? "exclusive" : "inclusive") + ") and " + bound(maximum) + " ("
                    + (exclusiveMaximum ? "exclusive" : "inclusive") + ")");
        }
        if (minimum != null) {
            return ValidationMessages.outOfBounds(
                    param + " must be greater than " + (exclusiveMinimum ? "" : "or equal to ") + bound(minimum));
        }
        return ValidationMessages.outOfBounds(
                param + " must be less than " + (exclusiveMaximum ? "" : "or equal to ") + bound(maximum));
    }

    private static JsonNode number(JsonNode bound) {
        return bound != null && bound.isNumber() ? bound : null;
    }

    /** A bound as WordPress prints it: PHP's integer conversion. */
    private static long bound(JsonNode bound) {
        return PhpValues.toLong(bound);
    }

    private static RestError checkString(String text, ObjectNode schema, String param) {
        JsonNode minLength = schema.get("minLength");
        JsonNode maxLength = schema.get("maxLength");
        if (minLength == null && maxLength == null) {
            return null;
        }
        long length = text.codePointCount(0, text.length());
        if (minLength != null && minLength.isNumber() && length < PhpValues.toLong(minLength)) {
            return ValidationMessages.tooShort(param, PhpValues.toLong(minLength));
        }
        if (maxLength != null && maxLength.isNumber() && length > PhpValues.toLong(maxLength)) {
            return ValidationMessages.tooLong(param, PhpValues.toLong(maxLength));
        }
        return null;
    }

    private static RestError checkArray(JsonNode value, ObjectNode schema, String param) {
        if (!isArray(value)) {
            return ValidationMessages.invalidType(param, "array");
        }
        List<JsonNode> items = listOf(value);
        if (schema.get("items") instanceof ObjectNode itemSchema) {
            for (int i = 0; i < items.size(); i++) {
                RestError error = check(items.get(i), itemSchema, param + "[" + i + "]");
                if (error != null) {
                    return error;
                }
            }
        }
        JsonNode minItems = schema.get("minItems");
        if (minItems != null && minItems.isNumber() && items.size() < PhpValues.toLong(minItems)) {
            return ValidationMessages.tooFewItems(param, PhpValues.toLong(minItems));
        }
        JsonNode maxItems = schema.get("maxItems");
        if (maxItems != null && maxItems.isNumber() && items.size() > PhpValues.toLong(maxItems)) {
            return ValidationMessages.tooManyItems(param, PhpValues.toLong(maxItems));
        }
        if (PhpValues.toBoolean(schema.get("uniqueItems")) && !unique(items)) {
            return ValidationMessages.duplicateItems(param);
        }
        return null;
    }

    /** Whether no two items are identical by PHP's strict comparison (an array's keys compared in sorted order). */
    static boolean unique(List<JsonNode> items) {
        Set<String> seen = new HashSet<>();
        for (JsonNode item : items) {
            StringBuilder key = new StringBuilder();
            identity(item, key);
            if (!seen.add(key.toString())) {
                return false;
            }
        }
        return true;
    }

    /** Writes a key that equals another's exactly when PHP finds the two values identical. */
    private static void identity(JsonNode value, StringBuilder key) {
        if (value.isNull()) {
            key.append('n');
        } else if (value.isBoolean()) {
            key.append(value.booleanValue() ? "b1" : "b0");
        } else if (value.isIntegralNumber() && value.canConvertToLong()) {
            key.append('i').append(value.longValue()).append(';');
        } else if (value.isNumber()) {
            double number = value.doubleValue();
            key.append('d').append(number == 0.0 ? 0.0 : number).append(';');
        } else if (value.isTextual()) {
            String text = value.textValue();
            key.append('s').append(text.length()).append(':').append(text);
        } else {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            if (value.isArray()) {
                for (int i = 0; i < value.size(); i++) {
                    entries.add(Map.entry(Integer.toString(i), value.get(i)));
                }
            } else {
                for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                    entries.add(it.next());
                }
                entries.sort((a, b) -> compareKeys(a.getKey(), b.getKey()));
            }
            key.append('a').append(entries.size()).append('{');
            for (Map.Entry<String, JsonNode> entry : entries) {
                String name = entry.getKey();
                key.append(PhpValues.integerKey(name) ? 'k' : 'K')
                        .append(name.length())
                        .append(':')
                        .append(name);
                identity(entry.getValue(), key);
            }
            key.append('}');
        }
    }

    /** Integer keys first, by value; then string keys. */
    private static int compareKeys(String a, String b) {
        boolean intA = PhpValues.integerKey(a);
        boolean intB = PhpValues.integerKey(b);
        if (intA && intB) {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        }
        if (intA != intB) {
            return intA ? -1 : 1;
        }
        return a.compareTo(b);
    }

    private static RestError checkObject(JsonNode value, ObjectNode schema, String param) {
        if (!isObject(value)) {
            return ValidationMessages.invalidType(param, "object");
        }
        ObjectNode object = SchemaSanitizer.asObject(value);
        JsonNode properties = schema.get("properties");
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode name : required) {
                if (!object.has(name.asText())) {
                    return ValidationMessages.propertyRequired(name.asText(), param);
                }
            }
        } else if (properties instanceof ObjectNode declared) {
            for (Iterator<Map.Entry<String, JsonNode>> it = declared.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> property = it.next();
                if (property.getValue() instanceof ObjectNode propertySchema
                        && PhpValues.toBoolean(propertySchema.get("required"))
                        && !object.has(property.getKey())) {
                    return ValidationMessages.propertyRequired(property.getKey(), param);
                }
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String name = entry.getKey();
            JsonNode propertySchema = properties instanceof ObjectNode declared ? declared.get(name) : null;
            RestError error = null;
            if (propertySchema instanceof ObjectNode known) {
                error = check(entry.getValue(), known, param + "[" + name + "]");
            } else if (additional != null && additional.isBoolean() && !additional.booleanValue()) {
                error = ValidationMessages.additionalPropertyForbidden(name);
            } else if (additional instanceof ObjectNode additionalSchema) {
                error = check(entry.getValue(), additionalSchema, param + "[" + name + "]");
            }
            if (error != null) {
                return error;
            }
        }
        JsonNode minProperties = schema.get("minProperties");
        if (minProperties != null && minProperties.isNumber() && object.size() < PhpValues.toLong(minProperties)) {
            return ValidationMessages.tooFewProperties(param, PhpValues.toLong(minProperties));
        }
        JsonNode maxProperties = schema.get("maxProperties");
        if (maxProperties != null && maxProperties.isNumber() && object.size() > PhpValues.toLong(maxProperties)) {
            return ValidationMessages.tooManyProperties(param, PhpValues.toLong(maxProperties));
        }
        return null;
    }

    private static RestError checkEnum(
            JsonNode value, ObjectNode schema, String type, ContainerNode<?> values, String param) {
        JsonNode sanitized = type == null ? value : SchemaSanitizer.sanitizeAs(value, schema, type);
        for (JsonNode allowed : values) {
            if (same(sanitized, allowed)) {
                return null;
            }
        }
        List<String> printed = new ArrayList<>(values.size());
        for (JsonNode allowed : values) {
            printed.add(
                    allowed.isNull() || PhpValues.isPhpArray(allowed)
                            ? new String(RestJson.encode(allowed, false), StandardCharsets.UTF_8)
                            : PhpValues.toPhpString(allowed));
        }
        return ValidationMessages.notInEnum(param, printed);
    }

    /**
     * WordPress's value equality for {@code enum}: numbers by value (an integer equals a float of the same value),
     * arrays by size and each key's value regardless of order, everything else strictly.
     */
    static boolean same(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            if (a.isIntegralNumber() && b.isIntegralNumber() && a.canConvertToLong() && b.canConvertToLong()) {
                return a.longValue() == b.longValue();
            }
            return a.doubleValue() == b.doubleValue();
        }
        if (PhpValues.isPhpArray(a) && PhpValues.isPhpArray(b)) {
            ObjectNode left = SchemaSanitizer.asObject(a);
            ObjectNode right = SchemaSanitizer.asObject(b);
            if (left.size() != right.size()) {
                return false;
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = left.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode other = right.get(entry.getKey());
                if (other == null || !same(entry.getValue(), other)) {
                    return false;
                }
            }
            return true;
        }
        if (a.isTextual() && b.isTextual()) {
            return a.textValue().equals(b.textValue());
        }
        if (a.isBoolean() && b.isBoolean()) {
            return a.booleanValue() == b.booleanValue();
        }
        return a.isNull() && b.isNull();
    }

    private static RestError checkFormat(JsonNode value, String format, String param) {
        if (PhpValues.isPhpArray(value)) {
            return switch (format) {
                case "hex-color" -> ValidationMessages.invalidHexColor();
                case "date-time" -> ValidationMessages.invalidDate();
                case "email" -> ValidationMessages.invalidEmail();
                case "ip" -> ValidationMessages.invalidIp(param);
                case "uuid" -> ValidationMessages.invalidUuid(param);
                default -> null;
            };
        }
        String text = PhpValues.toPhpString(value);
        return switch (format) {
            case "hex-color" -> Formats.hexColor(text) ? null : ValidationMessages.invalidHexColor();
            case "date-time" -> Formats.dateTime(text) ? null : ValidationMessages.invalidDate();
            case "email" -> Formats.email(text) ? null : ValidationMessages.invalidEmail();
            case "ip" -> Formats.ip(text) ? null : ValidationMessages.invalidIp(param);
            case "uuid" -> Formats.uuid(text) ? null : ValidationMessages.invalidUuid(param);
            default -> null;
        };
    }
}

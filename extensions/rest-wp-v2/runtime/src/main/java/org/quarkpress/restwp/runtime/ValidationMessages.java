package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.quarkpress.restwp.RestError;

/**
 * WordPress's English messages and codes for argument checks. Only a type error carries data ({@code {"param": ...}});
 * the others carry none, and none carries a status.
 */
public final class ValidationMessages {

    private ValidationMessages() {}

    /** An English list: {@code a}; {@code a and b}; {@code a, b, and c}. */
    public static String list(List<String> items) {
        int size = items.size();
        if (size == 0) {
            return "";
        }
        if (size == 1) {
            return items.get(0);
        }
        if (size == 2) {
            return items.get(0) + " and " + items.get(1);
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < size - 1; i++) {
            text.append(items.get(i)).append(", ");
        }
        return text.append("and ").append(items.get(size - 1)).toString();
    }

    static RestError invalidType(String param, String type) {
        ObjectNode data = RestJson.NODES.objectNode();
        data.put("param", param);
        return new RestError("rest_invalid_type", param + " is not of type " + type + ".", data, List.of(), List.of());
    }

    static RestError notInEnum(String param, List<String> values) {
        String message = values.size() == 1
                ? param + " is not " + values.get(0) + "."
                : param + " is not one of " + list(values) + ".";
        return RestError.noStatus("rest_not_in_enum", message);
    }

    static RestError outOfBounds(String message) {
        return RestError.noStatus("rest_out_of_bounds", message);
    }

    static RestError notMultiple(String param, JsonNode multipleOf) {
        return RestError.noStatus(
                "rest_invalid_multiple", param + " must be a multiple of " + PhpValues.toPhpString(multipleOf) + ".");
    }

    static RestError tooShort(String param, long length) {
        return RestError.noStatus(
                "rest_too_short",
                param + " must be at least " + length + " " + (length == 1 ? "character" : "characters") + " long.");
    }

    static RestError tooLong(String param, long length) {
        return RestError.noStatus(
                "rest_too_long",
                param + " must be at most " + length + " " + (length == 1 ? "character" : "characters") + " long.");
    }

    static RestError tooFewItems(String param, long count) {
        return RestError.noStatus(
                "rest_too_few_items",
                param + " must contain at least " + count + " " + (count == 1 ? "item" : "items") + ".");
    }

    static RestError tooManyItems(String param, long count) {
        return RestError.noStatus(
                "rest_too_many_items",
                param + " must contain at most " + count + " " + (count == 1 ? "item" : "items") + ".");
    }

    static RestError duplicateItems(String param) {
        return RestError.noStatus("rest_duplicate_items", param + " has duplicate items.");
    }

    static RestError propertyRequired(String property, String param) {
        return RestError.noStatus("rest_property_required", property + " is a required property of " + param + ".");
    }

    static RestError additionalPropertyForbidden(String property) {
        return RestError.noStatus(
                "rest_additional_properties_forbidden", property + " is not a valid property of Object.");
    }

    static RestError tooFewProperties(String param, long count) {
        return RestError.noStatus(
                "rest_too_few_properties",
                param + " must contain at least " + count + " " + (count == 1 ? "property" : "properties") + ".");
    }

    static RestError tooManyProperties(String param, long count) {
        return RestError.noStatus(
                "rest_too_many_properties",
                param + " must contain at most " + count + " " + (count == 1 ? "property" : "properties") + ".");
    }

    static RestError invalidDate() {
        return RestError.noStatus("rest_invalid_date", "Invalid date.");
    }

    static RestError invalidEmail() {
        return RestError.noStatus("rest_invalid_email", "Invalid email address.");
    }

    static RestError invalidHexColor() {
        return RestError.noStatus("rest_invalid_hex_color", "Invalid hex color.");
    }

    static RestError invalidIp(String param) {
        return RestError.noStatus("rest_invalid_ip", param + " is not a valid IP address.");
    }

    static RestError invalidUuid(String param) {
        return RestError.noStatus("rest_invalid_uuid", param + " is not a valid UUID.");
    }
}

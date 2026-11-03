package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.quarkpress.restwp.RestError;

/**
 * WordPress's English messages and codes for argument checks. A type error carries data ({@code {"param": ...}}) and
 * so do the {@code oneOf} failures (the matching or explaining schemas' positions); the others carry none, and none
 * carries a status.
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

    /**
     * A value several {@code oneOf} schemas accept: their titles, and their positions in the list as data.
     */
    static RestError multipleMatches(String param, List<String> titles, List<Integer> positions) {
        ObjectNode data = RestJson.NODES.objectNode();
        var list = data.putArray("positions");
        positions.forEach(list::add);
        return new RestError(
                "rest_one_of_multiple_matches",
                param + " matches " + list(titles) + ", but should match only one.",
                data,
                List.of(),
                List.of());
    }

    /** A value no {@code oneOf} schema accepts, explained by the failure of the one at {@code position}. */
    static RestError noMatchingSchema(String param, String title, RestError reason, int position) {
        ObjectNode data = RestJson.NODES.objectNode();
        data.put("position", position);
        return new RestError(
                "rest_no_matching_schema",
                param + " is not a valid " + title + ". Reason: " + reason.message(),
                data,
                List.of(),
                List.of());
    }

    /** A value no {@code oneOf} schema accepts, no single failure explaining it: every schema's title. */
    static RestError noMatchingSchemas(String param, List<String> titles) {
        return RestError.noStatus("rest_no_matching_schema", param + " is not a valid " + list(titles) + ".");
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

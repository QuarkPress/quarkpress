package org.quarkpress.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A REST error as WordPress builds it: a code, a message and data, plus the data the code held before
 * ({@code additionalData}, oldest first) and further errors ({@code additionalErrors}).
 *
 * @param data the error's data, or {@code null} when it has none
 */
public record RestError(
        String code, String message, JsonNode data, List<JsonNode> additionalData, List<RestError> additionalErrors) {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    public RestError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (data != null && data.isNull()) {
            data = null;
        }
        additionalData = List.copyOf(additionalData);
        additionalErrors = List.copyOf(additionalErrors);
    }

    /** An error whose data is {@code {"status": status}}. */
    public static RestError of(String code, String message, int status) {
        ObjectNode data = NODES.objectNode();
        data.put("status", status);
        return new RestError(code, message, data, List.of(), List.of());
    }

    /** An error without data. */
    public static RestError noStatus(String code, String message) {
        return new RestError(code, message, null, List.of(), List.of());
    }

    /** The error with {@code data} as its data; the previous data, if any, moves to the end of additionalData. */
    public RestError withData(JsonNode data) {
        List<JsonNode> older = new ArrayList<>(additionalData);
        if (this.data != null) {
            older.add(this.data);
        }
        return new RestError(code, message, data, older, additionalErrors);
    }

    /** The error with {@code other} appended to its additional errors. */
    public RestError withError(RestError other) {
        List<RestError> more = new ArrayList<>(additionalErrors);
        more.add(Objects.requireNonNull(other, "other"));
        return new RestError(code, message, data, additionalData, more);
    }

    /** The last numeric {@code status} across all data, this error's first, then each additional error's; 500. */
    public int status() {
        int[] status = {-1};
        collectStatus(status);
        return status[0] < 0 ? 500 : status[0];
    }

    private void collectStatus(int[] status) {
        for (JsonNode older : additionalData) {
            statusOf(older, status);
        }
        statusOf(data, status);
        for (RestError other : additionalErrors) {
            other.collectStatus(status);
        }
    }

    private static void statusOf(JsonNode data, int[] status) {
        if (data != null && data.isObject() && data.get("status") instanceof JsonNode value) {
            if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0) {
                status[0] = value.intValue();
            } else if (value.isTextual()) {
                String text = value.textValue();
                int parsed = 0;
                boolean digits = !text.isEmpty() && text.length() <= 3;
                for (int i = 0; digits && i < text.length(); i++) {
                    char c = text.charAt(i);
                    digits = c >= '0' && c <= '9';
                    parsed = parsed * 10 + (c - '0');
                }
                if (digits) {
                    status[0] = parsed;
                }
            }
        }
    }

    /** {@code code, message, data}, then {@code additional_data} and {@code additional_errors} when not empty. */
    public ObjectNode toJson() {
        ObjectNode json = body();
        if (!additionalErrors.isEmpty()) {
            ArrayNode errors = json.putArray("additional_errors");
            for (RestError other : additionalErrors) {
                errors.add(other.body());
            }
        }
        return json;
    }

    private ObjectNode body() {
        ObjectNode json = NODES.objectNode();
        json.put("code", code);
        json.put("message", message);
        json.set("data", data == null ? NullNode.getInstance() : data.deepCopy());
        if (!additionalData.isEmpty()) {
            ArrayNode older = json.putArray("additional_data");
            additionalData.forEach(value -> older.add(value.deepCopy()));
        }
        return json;
    }
}

package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.RestResponse;

/**
 * WordPress's {@code _envelope}: the answer becomes status 200 with the body {@code {"body", "status", "headers"}},
 * and the answer's own headers travel in that object instead of as HTTP headers.
 */
public final class Envelope {

    private static final List<String> NUMERIC = List.of("X-WP-Total", "X-WP-TotalPages");

    private Envelope() {}

    /**
     * {@code response} enveloped: its body, its status, and its own headers as an object in the order they were set
     * ({@code X-WP-Total} and {@code X-WP-TotalPages} as integers, a header with several values as a list), or
     * {@code []} when it has none.
     */
    public static RestResponse wrap(RestResponse response) {
        ObjectNode envelope = RestJson.NODES.objectNode();
        envelope.set("body", response.body());
        envelope.put("status", response.status());
        if (response.headers().isEmpty()) {
            envelope.putArray("headers");
        } else {
            ObjectNode headers = envelope.putObject("headers");
            for (Map.Entry<String, List<String>> header : response.headers().entrySet()) {
                headers.set(header.getKey(), value(header.getKey(), header.getValue()));
            }
        }
        return new RestResponse(200, envelope, Map.of(), Links.none());
    }

    private static JsonNode value(String name, List<String> values) {
        if (values.size() != 1) {
            var list = RestJson.NODES.arrayNode();
            values.forEach(list::add);
            return list;
        }
        String value = values.get(0);
        if (isNumericHeader(name) && isSmallInteger(value)) {
            return RestJson.NODES.numberNode(Long.parseLong(value));
        }
        return RestJson.NODES.textNode(value);
    }

    private static boolean isNumericHeader(String name) {
        for (String numeric : NUMERIC) {
            if (numeric.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Decimal digits only, at most 18 of them (so the value fits a long). */
    private static boolean isSmallInteger(String value) {
        if (value.isEmpty() || value.length() > 18) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}

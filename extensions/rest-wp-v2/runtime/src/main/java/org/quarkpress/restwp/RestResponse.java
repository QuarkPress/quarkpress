package org.quarkpress.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A handler's answer: status, body, headers (in the order set) and links.
 *
 * @param headers header values by name; {@link #withHeader} replaces a header's values
 */
public record RestResponse(int status, JsonNode body, Map<String, List<String>> headers, Links links) {

    public RestResponse {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(links, "links");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        headers = Collections.unmodifiableMap(copy);
    }

    public static RestResponse ok(JsonNode body) {
        return new RestResponse(200, body, Map.of(), Links.none());
    }

    public static RestResponse created(JsonNode body, String location) {
        return new RestResponse(201, body, Map.of(), Links.none()).withHeader("Location", location);
    }

    public RestResponse withStatus(int status) {
        return new RestResponse(status, body, headers, links);
    }

    public RestResponse withHeader(String name, String value) {
        Map<String, List<String>> more = new LinkedHashMap<>(headers);
        more.put(name, new ArrayList<>(List.of(value)));
        return new RestResponse(status, body, more, links);
    }

    public RestResponse withLinks(Links links) {
        return new RestResponse(status, body, headers, links);
    }
}

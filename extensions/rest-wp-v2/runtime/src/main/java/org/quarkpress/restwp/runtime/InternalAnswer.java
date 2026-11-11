package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The answer of a request the REST server dispatched internally ({@link RestServer#dispatchInternal}), as WordPress
 * keeps a preloaded request's answer: its status, its data (links rendered into {@code _links}, embeds and
 * {@code _fields} applied) and only the answer's own headers, such as {@code Allow}, {@code X-WP-Total},
 * {@code X-WP-TotalPages}, a pagination {@code Link} or {@code Location}; never the server's fixed, cache or
 * cross-origin headers.
 *
 * @param status the HTTP status
 * @param data the answer's data, an error object for a refusal
 * @param headers header values by name, in the order the answer set them
 */
public record InternalAnswer(int status, JsonNode data, Map<String, List<String>> headers) {

    public InternalAnswer {
        Objects.requireNonNull(data, "data");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        headers = Collections.unmodifiableMap(copy);
    }
}

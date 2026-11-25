package org.quarkword.front;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@link FrontRenderer} answers a front-end request with: the status, the headers (each name with its values,
 * in order; they are added to the headers the front end already computed, and replace a header of the same name) and
 * the body. A {@code HEAD} request is sent the status and headers only.
 */
public record FrontAnswer(int status, Map<String, List<String>> headers, byte[] body) {

    public FrontAnswer {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        headers = Collections.unmodifiableMap(copy);
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}

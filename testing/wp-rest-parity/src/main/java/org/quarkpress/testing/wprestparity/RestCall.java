package org.quarkpress.testing.wprestparity;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A request as a user names it: who sends it ({@code "anonymous"}, a {@link WordPressRestSite#USERS} entry, or
 * {@code "invalid"}, a credential the server must refuse), the method, the target (path and query, starting with
 * {@code /}), extra headers, and an optional body with its content type.
 */
public record RestCall(
        String as,
        String method,
        String target,
        Map<String, String> headers,
        Optional<String> contentType,
        Optional<byte[]> body) {

    public RestCall {
        Objects.requireNonNull(as, "as");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
        if (!target.startsWith("/")) {
            throw new IllegalArgumentException("a target starts with '/': " + target);
        }
        headers = Map.copyOf(headers);
        body = body.map(byte[]::clone);
    }

    /** A GET of {@code target} with no extra headers and no body. */
    public static RestCall get(String as, String target) {
        return new RestCall(as, "GET", target, Map.of(), Optional.empty(), Optional.empty());
    }

    /** A request carrying {@code json} as an {@code application/json} body. */
    public static RestCall json(String as, String method, String target, String json) {
        return new RestCall(
                as,
                method,
                target,
                Map.of(),
                Optional.of("application/json"),
                Optional.of(json.getBytes(StandardCharsets.UTF_8)));
    }
}

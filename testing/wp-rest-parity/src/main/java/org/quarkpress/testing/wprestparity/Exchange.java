package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.testing.wporacle.OracleJson;

/** A request and the response it got: the status, the header lines by name, and the body as sent. */
public record Exchange(RestCall call, int status, Map<String, List<String>> headers, byte[] body) {

    public Exchange {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    /** The header's lines joined with {@code ", "}, the name compared without case; empty when it is absent. */
    public Optional<String> header(String name) {
        List<String> lines = headerLines(name);
        return lines.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", lines));
    }

    /** Every line of the header, in the order received, the name compared without case. */
    public List<String> headerLines(String name) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase(name)) {
                lines.addAll(header.getValue());
            }
        }
        return List.copyOf(lines);
    }

    /** The body parsed as JSON; an {@link AssertionError} names the call and shows the body when it is not JSON. */
    public JsonNode json() {
        try {
            JsonNode node = OracleJson.MAPPER.readTree(body);
            if (node == null || node.isMissingNode()) {
                throw new IOException("the body is empty");
            }
            return node;
        } catch (IOException e) {
            String text = text();
            throw new AssertionError(
                    call.method() + " " + call.target() + " (status " + status + ") did not answer JSON: "
                            + e.getMessage() + "\n" + (text.length() <= 2000 ? text : text.substring(0, 2000)),
                    e);
        }
    }

    /** The body decoded as UTF-8. */
    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }
}

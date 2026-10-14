package org.quarkpress.lib.wpblocks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Attribute JSON helpers: reading it, writing it comment-safe. Locating the attribute text inside a
 * delimiter is done by {@link AttributeEnds} (first {@code }} followed by whitespace and {@code -->}).
 */
final class AttributeJson {

    /**
     * Shared, immutable mapper. Only {@code readTree} and tree writing are used: no data binding,
     * no polymorphic typing, no modules. Default {@code StreamReadConstraints} apply.
     */
    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private AttributeJson() {}

    static ObjectNode emptyObject() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** Parses attribute text; anything that is not exactly one JSON object yields an empty object. */
    static ObjectNode parse(String raw) {
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node instanceof ObjectNode o ? o : emptyObject();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return emptyObject();
        }
    }

    /**
     * Writes compact JSON and then replaces the sequences that could end the HTML comment or inject
     * markup with JSON unicode escapes: {@code --}, {@code <}, {@code >}, {@code &} and the escaped
     * quote {@code \"}. The result is still valid JSON for the same value.
     */
    static String write(ObjectNode node) {
        String json;
        try {
            json = MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize attributes", e);
        }
        StringBuilder out = new StringBuilder(json.length() + 16);
        int n = json.length();
        for (int i = 0; i < n; i++) {
            char c = json.charAt(i);
            switch (c) {
                case '\\' -> {
                    // an escape produced by Jackson: two characters, never split them
                    char next = json.charAt(i + 1);
                    if (next == '"') {
                        out.append("\\u0022");
                    } else {
                        out.append(c).append(next);
                    }
                    i++;
                }
                case '-' -> {
                    if (i + 1 < n && json.charAt(i + 1) == '-') {
                        out.append("\\u002d\\u002d");
                        i++;
                    } else {
                        out.append(c);
                    }
                }
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}

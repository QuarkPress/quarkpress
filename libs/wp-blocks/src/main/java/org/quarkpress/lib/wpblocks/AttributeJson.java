package org.quarkpress.lib.wpblocks;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Attribute JSON helpers: reading it, writing it as WordPress does ({@link WordPressJson}). Locating the attribute
 * text inside a delimiter is done by {@link AttributeEnds} (first {@code }} followed by whitespace and {@code -->}).
 */
final class AttributeJson {

    /**
     * Shared, immutable mapper. Only {@code readTree} is used. Strings and names have no length limit, as in PHP;
     * nesting (1000) and number length keep Jackson's defaults for safety, and {@link PhpJsonRules} applies
     * PHP's stricter nesting limit. A number whose integer, fraction or exponent part exceeds 1000 characters makes
     * the attributes invalid, floats included, where PHP accepts it (D9).
     */
    static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxStringLength(Integer.MAX_VALUE)
                            .maxNameLength(Integer.MAX_VALUE)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private AttributeJson() {}

    static ObjectNode emptyObject() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** Parses attribute text as PHP's {@code json_decode} would; null when PHP would reject it. */
    static ObjectNode parseOrNull(String raw) {
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
        return node instanceof ObjectNode o && PhpJsonRules.accepts(o) ? o : null;
    }

    /** Like {@link #parseOrNull}, with an empty object for text PHP would reject. */
    static ObjectNode parse(String raw) {
        ObjectNode parsed = parseOrNull(raw);
        return parsed != null ? parsed : emptyObject();
    }

    /** Writes attributes as WordPress's block serializer does; the caller's tree is not modified. */
    static String write(ObjectNode node) {
        return WordPressJson.write(node);
    }
}

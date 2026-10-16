package org.quarkpress.lib.wpblocks;

import com.fasterxml.jackson.core.JsonProcessingException;
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
     * Shared, immutable mapper. Only {@code readTree} is used: no data binding, no polymorphic typing, no modules.
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

    /** Writes attributes as WordPress's block serializer does; the caller's tree is not modified. */
    static String write(ObjectNode node) {
        return WordPressJson.write(node);
    }
}

package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Predicate;
import org.quarkpress.core.WordPressKeys;
import org.quarkpress.lib.wpkses.Kses;
import org.quarkpress.posts.MetaContext;
import org.quarkpress.posts.MetaSanitizer;

/**
 * WordPress's sanitizing of the {@code footnotes} meta: a caller with {@code unfiltered_html} stores the text as
 * given; for anyone else the text is decoded as JSON, anything but a list becomes "", entries without an id and a
 * content that are not empty in PHP's sense are dropped, the id is key-sanitized, the content passes post kses, and
 * the list is encoded again as PHP does ({@link PhpJsonText}). Whether the caller holds {@code unfiltered_html} is
 * asked on every call.
 */
public final class FootnotesSanitizer implements MetaSanitizer {

    /** Jackson's default parser, which like PHP keeps the last of duplicate keys. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Kses kses = Kses.wordPress();
    private final Predicate<MetaContext> unfilteredHtml;

    public FootnotesSanitizer(Predicate<MetaContext> unfilteredHtml) {
        this.unfilteredHtml = Objects.requireNonNull(unfilteredHtml, "unfilteredHtml");
    }

    @Override
    public JsonNode sanitize(JsonNode value, MetaContext context) {
        if (unfilteredHtml.test(context)) {
            return value;
        }
        JsonNode decoded = decode(text(value));
        if (decoded == null || !decoded.isArray()) {
            return TextNode.valueOf("");
        }
        ArrayNode kept = NODES.arrayNode();
        for (JsonNode entry : decoded) {
            if (!entry.isObject()) {
                continue;
            }
            JsonNode id = entry.get("id");
            JsonNode content = entry.get("content");
            if (phpEmpty(id) || phpEmpty(content) || !id.isValueNode() || !content.isValueNode()) {
                continue;
            }
            ObjectNode footnote = kept.addObject();
            footnote.put("id", WordPressKeys.sanitize(text(id)));
            footnote.put("content", kses.post(text(content)));
        }
        return TextNode.valueOf(PhpJsonText.encode(kept));
    }

    /** The text as JSON, or null when it is not JSON (PHP's {@code json_decode} answers null). */
    private static JsonNode decode(String text) {
        try (JsonParser parser = JSON.createParser(text)) {
            JsonNode decoded = JSON.readTree(parser);
            if (decoded == null || decoded.isMissingNode() || parser.nextToken() != null) {
                return null;
            }
            return decoded;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** PHP's {@code empty}: null, false, 0, "", "0" and an empty array are empty. */
    private static boolean phpEmpty(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return true;
        }
        if (value.isBoolean()) {
            return !value.booleanValue();
        }
        if (value.isNumber()) {
            return value.decimalValue().signum() == 0;
        }
        if (value.isTextual()) {
            return PostRules.phpEmpty(value.textValue());
        }
        return value.isContainerNode() && value.isEmpty();
    }

    /** A scalar as PHP's string: true "1", false "", a number its PHP text; a container or null "". */
    private static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode() || value.isContainerNode()) {
            return "";
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "1" : "";
        }
        if (value.isNumber() && !value.isIntegralNumber()) {
            return PhpJsonText.encode(value);
        }
        return value.asText();
    }
}

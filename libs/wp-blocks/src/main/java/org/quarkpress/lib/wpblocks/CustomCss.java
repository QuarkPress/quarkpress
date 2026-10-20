package org.quarkpress.lib.wpblocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;

/**
 * Removes block custom CSS from block markup, as WordPress does when content is saved by a user who may not edit CSS:
 * every opening or void delimiter whose attributes hold a {@code css} entry in a {@code style} object loses that
 * entry, an emptied {@code style} goes too, and attributes left empty are removed with the character after them. The
 * rest of the text is kept byte for byte; changed attributes are written as WordPress writes decoded and encoded
 * attributes (an empty object becomes an empty list, an object keyed 0, 1, ... becomes a list, and an integer beyond
 * 64 bits becomes the float PHP reads it as). Attributes holding a number too large for a float are removed altogether.
 */
final class CustomCss {

    private static final String BLOCK_MARK = "<!-- wp:";

    private CustomCss() {}

    static String strip(String content) {
        if (!content.contains(BLOCK_MARK)) {
            return content;
        }
        AttributeEnds ends = new AttributeEnds(content);
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (true) {
            int at = content.indexOf(DelimiterScanner.COMMENT_OPEN, from);
            if (at < 0) {
                break;
            }
            DelimiterScanner.Delimiter delimiter = DelimiterScanner.tryRead(content, at, ends);
            if (delimiter == null) {
                from = at + 1;
                continue;
            }
            from = delimiter.end();
            if (delimiter.kind() == DelimiterScanner.Kind.CLOSER
                    || delimiter.rawAttributes() == null
                    || !delimiter.attributesDecodable()) {
                continue;
            }
            ObjectNode attributes = AttributeJson.parseOrNull(delimiter.rawAttributes());
            if (attributes == null
                    || !(attributes.get("style") instanceof ObjectNode style)
                    || style.get("css") == null
                    || style.get("css").isNull()) {
                continue;
            }
            ObjectNode changed = attributes.deepCopy();
            ObjectNode changedStyle = (ObjectNode) changed.get("style");
            changedStyle.remove("css");
            if (changedStyle.isEmpty()) {
                changed.remove("style");
            }
            int jsonStart = content.indexOf('{', at);
            int jsonEnd = jsonStart + delimiter.rawAttributes().length();
            if (out == null) {
                out = new StringBuilder(content.length());
            }
            out.append(content, copied, jsonStart);
            if (changed.isEmpty()) {
                copied = Math.min(jsonEnd + 1, content.length());
            } else {
                // A number that overflows to infinity cannot be encoded; WordPress then writes no attributes at all.
                if (!hasInfinity(changed)) {
                    out.append(WordPressJson.write(asPhpRoundTrip(changed)));
                }
                copied = jsonEnd;
            }
        }
        if (out == null) {
            return content;
        }
        return out.append(content, copied, content.length()).toString();
    }

    /** The value PHP's decode-to-arrays and encode give back for {@code node}. */
    private static JsonNode asPhpRoundTrip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.isEmpty() || sequentialKeys(object)) {
                ArrayNode list = JsonNodeFactory.instance.arrayNode();
                object.forEach(value -> list.add(asPhpRoundTrip(value)));
                return list;
            }
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                copy.set(entry.getKey(), asPhpRoundTrip(entry.getValue()));
            }
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            array.forEach(value -> copy.add(asPhpRoundTrip(value)));
            return copy;
        }
        if (node.isBigInteger() && node.bigIntegerValue().bitLength() > 63) {
            return JsonNodeFactory.instance.numberNode(node.doubleValue());
        }
        return node;
    }

    private static boolean hasInfinity(JsonNode node) {
        if (node.isFloatingPointNumber() || node.isBigInteger()) {
            return !Double.isFinite(node.doubleValue());
        }
        for (JsonNode child : node) {
            if (hasInfinity(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sequentialKeys(ObjectNode object) {
        int expected = 0;
        for (Iterator<String> names = object.fieldNames(); names.hasNext(); ) {
            if (!names.next().equals(Integer.toString(expected))) {
                return false;
            }
            expected++;
        }
        return true;
    }
}

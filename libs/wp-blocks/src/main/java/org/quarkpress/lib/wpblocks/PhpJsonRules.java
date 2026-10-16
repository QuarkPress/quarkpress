package org.quarkpress.lib.wpblocks;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * The two places where PHP's {@code json_decode} (used by WordPress for block attributes) is stricter than
 * Jackson: containers may nest at most {@value #MAX_NESTING} deep (its default depth of 512 counts one level more),
 * and a UTF-16 surrogate escape must be half of a pair.
 */
final class PhpJsonRules {

    static final int MAX_NESTING = 511;

    private PhpJsonRules() {}

    static boolean accepts(JsonNode root) {
        return accepts(root, 1);
    }

    private static boolean accepts(JsonNode node, int depth) {
        if (node.isContainerNode() && depth > MAX_NESTING) {
            return false;
        }
        if (node.isTextual()) {
            return pairedSurrogates(node.textValue());
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if (!pairedSurrogates(e.getKey()) || !accepts(e.getValue(), depth + 1)) {
                    return false;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (!accepts(child, depth + 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean pairedSurrogates(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}

package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.quarkpress.persistence.runtime.JsonValues;

/**
 * Meta rows as WordPress's meta functions treat them, on a post's meta object (key to array of values). Values compare
 * by value; keys keep their order.
 */
public final class MetaRows {

    private MetaRows() {}

    /** A key WordPress accepts: not empty and not "0". */
    public static boolean validKey(String key) {
        return !PostRules.phpEmpty(key);
    }

    /**
     * Whether only the system identity may write {@code key}: its first character that is printable ASCII is an
     * underscore. Leading characters outside printable ASCII (control characters, zero-width spaces, any non-ASCII
     * character) do not hide the underscore.
     */
    public static boolean protectedKey(String key) {
        int length = key.length();
        int i = 0;
        while (i < length) {
            int codePoint = key.codePointAt(i);
            if (codePoint >= 0x20 && codePoint <= 0x7E) {
                return codePoint == '_';
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    /** A copy of a stored meta value; anything that is not an object counts as no meta. */
    public static ObjectNode copy(JsonNode meta) {
        return meta != null && meta.isObject() ? ((ObjectNode) meta).deepCopy() : JsonNodeFactory.instance.objectNode();
    }

    /** The values of {@code key}, in order (copies). */
    public static List<JsonNode> values(JsonNode meta, String key) {
        List<JsonNode> values = new ArrayList<>();
        JsonNode list = meta == null ? null : meta.get(key);
        if (list != null && list.isArray()) {
            list.forEach(value -> values.add(value.deepCopy()));
        }
        return values;
    }

    /** {@code add_post_meta}: appends the value; with {@code unique}, refused when the key has values. */
    public static boolean add(ObjectNode meta, String key, JsonNode value, boolean unique) {
        JsonNode list = meta.get(key);
        if (unique && list != null && list.isArray() && !list.isEmpty()) {
            return false;
        }
        ArrayNode values = list != null && list.isArray() ? (ArrayNode) list : meta.putArray(key);
        values.add(value.deepCopy());
        return true;
    }

    /**
     * {@code update_post_meta}: adds the value when the key has none; otherwise every value of the key becomes
     * {@code value}. Returns false when nothing changed.
     */
    public static boolean update(ObjectNode meta, String key, JsonNode value) {
        JsonNode list = meta.get(key);
        if (list == null || !list.isArray() || list.isEmpty()) {
            return add(meta, key, value, false);
        }
        boolean changed = false;
        ArrayNode values = (ArrayNode) list;
        for (int i = 0; i < values.size(); i++) {
            if (!JsonValues.sameValue(values.get(i), value)) {
                values.set(i, value.deepCopy());
                changed = true;
            }
        }
        return changed;
    }

    private static boolean meansEveryValue(JsonNode value) {
        return value == null
                || value.isNull()
                || (value.isTextual() && value.textValue().isEmpty())
                || (value.isBoolean() && !value.booleanValue());
    }

    /** {@code delete_post_meta} of every value of {@code key}. */
    public static boolean delete(ObjectNode meta, String key) {
        return meta.remove(key) != null;
    }

    /**
     * {@code delete_post_meta} of the values of {@code key} equal to {@code value}; an emptied key goes too. As in
     * WordPress, a value that is null (Java or JSON), the empty string or false deletes every value of the key.
     */
    public static boolean delete(ObjectNode meta, String key, JsonNode value) {
        if (meansEveryValue(value)) {
            return delete(meta, key);
        }
        JsonNode list = meta.get(key);
        if (list == null || !list.isArray()) {
            return false;
        }
        ArrayNode kept = JsonNodeFactory.instance.arrayNode();
        list.forEach(existing -> {
            if (!JsonValues.sameValue(existing, value)) {
                kept.add(existing);
            }
        });
        if (kept.size() == list.size()) {
            return false;
        }
        if (kept.isEmpty()) {
            meta.remove(key);
        } else {
            meta.set(key, kept);
        }
        return true;
    }
}

package org.quarkpress.testing.wporacle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * The typed PHP value codec shared with the drivers ({@code qp_tree()} and {@code qp_untree()} in
 * bootstrap.php). Plain JSON loses int-versus-string keys, {@code -0} and raw bytes, so every value travels as
 * an object with a type tag {@code t}:
 *
 * <ul>
 *   <li>{@code null}; {@code bool} with {@code v}; {@code int} with {@code v} as decimal text;
 *   <li>{@code float} with {@code v}: from PHP the text {@code serialize()} writes, towards PHP any text a
 *       {@code (float)} cast reads, or {@code INF}, {@code -INF}, {@code NAN};
 *   <li>{@code string} with {@code b64}, the bytes in base64;
 *   <li>{@code array} with {@code e}, a list of {@code [key, value]} pairs whose keys are {@code int} or
 *       {@code string} trees; towards PHP, a key {@code append} means {@code $array[] = value};
 *   <li>{@code object} with class {@code c} and {@code e}; {@code enum} with class {@code c} and case {@code v};
 *   <li>QuarkPress-side results only: {@code custom} (a {@code C:} payload kept as data, class {@code c},
 *       {@code b64}), {@code cycle} (a value that contains itself), {@code failed} (unserialize failed).
 * </ul>
 */
public final class PhpTree {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** One array or object entry. */
    public record Entry(ObjectNode key, ObjectNode value) {
        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    private PhpTree() {}

    public static ObjectNode nul() {
        return typed("null");
    }

    public static ObjectNode bool(boolean value) {
        ObjectNode node = typed("bool");
        node.put("v", value);
        return node;
    }

    public static ObjectNode integer(long value) {
        ObjectNode node = typed("int");
        node.put("v", Long.toString(value));
        return node;
    }

    public static ObjectNode floating(String text) {
        ObjectNode node = typed("float");
        node.put("v", text);
        return node;
    }

    public static ObjectNode string(byte[] bytes) {
        ObjectNode node = typed("string");
        node.put("b64", Base64.getEncoder().encodeToString(bytes));
        return node;
    }

    public static ObjectNode string(String utf8) {
        return string(utf8.getBytes(StandardCharsets.UTF_8));
    }

    public static ObjectNode append() {
        return typed("append");
    }

    public static ObjectNode array(List<Entry> entries) {
        ObjectNode node = typed("array");
        node.set("e", pairs(entries));
        return node;
    }

    public static ObjectNode object(String className, List<Entry> properties) {
        ObjectNode node = typed("object");
        node.put("c", className);
        node.set("e", pairs(properties));
        return node;
    }

    public static ObjectNode enumCase(String className, String caseName) {
        ObjectNode node = typed("enum");
        node.put("c", className);
        node.put("v", caseName);
        return node;
    }

    public static ObjectNode custom(String className, byte[] payload) {
        ObjectNode node = typed("custom");
        node.put("c", className);
        node.put("b64", Base64.getEncoder().encodeToString(payload));
        return node;
    }

    public static ObjectNode cycle() {
        return typed("cycle");
    }

    public static ObjectNode failed() {
        return typed("failed");
    }

    /** The type tag of a tree node. */
    public static String type(JsonNode node) {
        return node.path("t").asText();
    }

    /** The bytes of a {@code string} or {@code custom} node. */
    public static byte[] bytes(JsonNode node) {
        return Base64.getDecoder().decode(node.path("b64").asText());
    }

    private static ObjectNode typed(String type) {
        ObjectNode node = NODES.objectNode();
        node.put("t", type);
        return node;
    }

    private static ArrayNode pairs(List<Entry> entries) {
        ArrayNode array = NODES.arrayNode();
        for (Entry entry : entries) {
            ArrayNode pair = array.addArray();
            pair.add(entry.key());
            pair.add(entry.value());
        }
        return array;
    }
}

package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.quarkpress.restwp.PhpObjectNode;

/**
 * WordPress's server-level {@code _fields} reduction. The requested fields become a key tree, built in one pass over
 * the list: each field's dot-separated parts are a path, its last part keeping the whole value. A field whose path
 * runs through a part already kept whole ends the list there: that field and every later one are ignored (WordPress
 * does so). A list body is reduced item by item (so is an object keyed by integers, which PHP holds as a list: its
 * keys are renumbered); any other object is reduced to the keys of the tree, a kept key whose tree has children and
 * whose value is an array being reduced the same way, by its keys or indexes. A reduced array that keeps nothing is
 * {@code []}; a reduced list that keeps indexes other than 0, 1, 2 and so on prints as an object, as PHP prints it.
 * An object WordPress builds as a PHP object ({@link PhpObjectNode}) is kept whole wherever it is reached (as the
 * whole body, or as an item of a list body, WordPress fails instead; it is kept as it is, as are scalar bodies and
 * items). The work is linear in the field list and in the part of the data the tree reaches, and nothing recurses on
 * the call stack.
 */
public final class FieldsFilter {

    /** A part of the tree: either {@link #ALL} (the whole value is kept) or a node with children. */
    private static final class Node {
        final Map<String, Node> children;

        Node(Map<String, Node> children) {
            this.children = children;
        }
    }

    /** The whole value is kept: a sentinel with no (and no room for) children. */
    private static final Node ALL = new Node(Map.of());

    /** A reduction still to do: {@code source} reduced by {@code tree} into {@code target} at {@code key}. */
    private record Pending(JsonNode source, Node tree, JsonNode target, String key, int index) {}

    private FieldsFilter() {}

    /** {@code data} reduced to {@code fields}; {@code data} itself when the list is empty. The input is not changed. */
    public static JsonNode reduce(JsonNode data, List<String> fields) {
        return reduce(data, fields, new long[1]);
    }

    /**
     * {@link #reduce(JsonNode, List)}, adding to {@code work[0]} each character of the fields and each entry looked
     * at.
     */
    static JsonNode reduce(JsonNode data, List<String> fields, long[] work) {
        if (fields.isEmpty()) {
            return data;
        }
        Node tree = tree(fields, work);
        Deque<Pending> pending = new ArrayDeque<>();
        JsonNode reduced;
        if (data instanceof ArrayNode list) {
            ArrayNode items = RestJson.NODES.arrayNode(list.size());
            for (int i = 0; i < list.size(); i++) {
                work[0]++;
                items.add(NullNode.getInstance());
                pending.push(new Pending(list.get(i), tree, items, null, i));
            }
            reduced = items;
        } else if (data instanceof ObjectNode object
                && !(data instanceof PhpObjectNode)
                && PhpValues.hasIntegerKeys(object)) {
            ArrayNode items = RestJson.NODES.arrayNode(object.size());
            int i = 0;
            for (Iterator<JsonNode> values = object.elements(); values.hasNext(); i++) {
                work[0]++;
                items.add(NullNode.getInstance());
                pending.push(new Pending(values.next(), tree, items, null, i));
            }
            reduced = items;
        } else {
            reduced = intersect(data, tree, pending, work);
        }
        while (!pending.isEmpty()) {
            Pending next = pending.pop();
            JsonNode value = intersect(next.source(), next.tree(), pending, work);
            if (next.target() instanceof ArrayNode array) {
                array.set(next.index(), value);
            } else {
                ((ObjectNode) next.target()).set(next.key(), value);
            }
        }
        return reduced;
    }

    /**
     * WordPress's parsing of a {@code _fields} value (see {@code Fields.parse}), adding to {@code work[0]} each
     * character looked at.
     */
    public static List<String> parse(JsonNode value, long[] work) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        if (value.isContainerNode()) {
            for (JsonNode item : PhpValues.values(value)) {
                work[0]++;
                if (item.isValueNode() && !item.isNull()) {
                    fields.add(trim(PhpValues.toPhpString(item), work));
                }
            }
            return fields;
        }
        for (String piece : PhpValues.parseList(PhpValues.toPhpString(value), work)) {
            fields.add(trim(piece, work));
        }
        return fields;
    }

    /** PHP's {@code trim}: spaces, tabs, line feeds, carriage returns, NUL and vertical tabs off both ends. */
    private static String trim(String text, long[] work) {
        int start = 0;
        int end = text.length();
        while (start < end && trimmed(text.charAt(start))) {
            work[0]++;
            start++;
        }
        while (end > start && trimmed(text.charAt(end - 1))) {
            work[0]++;
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean trimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0 || c == 0x0B;
    }

    /** The key tree of {@code fields}, in one pass; a path through a part kept whole ends the list. */
    private static Node tree(List<String> fields, long[] work) {
        Node root = new Node(new HashMap<>());
        for (String field : fields) {
            Node node = root;
            int start = 0;
            int dot;
            while ((dot = field.indexOf('.', start)) >= 0) {
                work[0] += dot - start + 1;
                String part = field.substring(start, dot);
                Node child = node.children.get(part);
                if (child == ALL) {
                    return root;
                }
                if (child == null) {
                    child = new Node(new HashMap<>());
                    node.children.put(part, child);
                }
                node = child;
                start = dot + 1;
            }
            work[0] += field.length() - start + 1;
            node.children.put(field.substring(start), ALL);
        }
        return root;
    }

    /**
     * WordPress's recursive key intersection of {@code data} with {@code tree}: the entries whose key is in the tree,
     * in the data's order; the reduction of a kept array whose tree has children is queued on {@code pending}. Any
     * other value is kept as it is.
     */
    private static JsonNode intersect(JsonNode data, Node tree, Deque<Pending> pending, long[] work) {
        if (tree == ALL || data instanceof PhpObjectNode) {
            return data;
        }
        if (data instanceof ObjectNode object) {
            List<Map.Entry<String, JsonNode>> kept = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : object.properties()) {
                work[0]++;
                if (tree.children.containsKey(entry.getKey())) {
                    kept.add(entry);
                }
            }
            if (kept.isEmpty()) {
                return RestJson.NODES.arrayNode();
            }
            List<String> keys = new ArrayList<>(kept.size());
            List<JsonNode> values = new ArrayList<>(kept.size());
            for (Map.Entry<String, JsonNode> entry : kept) {
                keys.add(entry.getKey());
                values.add(entry.getValue());
            }
            return assemble(keys, values, tree, sequence(keys), pending);
        }
        if (data instanceof ArrayNode array) {
            List<String> keys = new ArrayList<>();
            List<JsonNode> values = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                work[0]++;
                String key = Integer.toString(i);
                if (tree.children.containsKey(key)) {
                    keys.add(key);
                    values.add(array.get(i));
                }
            }
            if (keys.isEmpty()) {
                return RestJson.NODES.arrayNode();
            }
            return assemble(
                    keys, values, tree, Integer.parseInt(keys.get(keys.size() - 1)) == keys.size() - 1, pending);
        }
        return data;
    }

    /**
     * The kept entries as a list (keys 0, 1, 2 and so on in order, as PHP prints them) or an object, each array value
     * whose tree has children queued for its own reduction.
     */
    private static JsonNode assemble(
            List<String> keys, List<JsonNode> values, Node tree, boolean asList, Deque<Pending> pending) {
        JsonNode target = asList ? RestJson.NODES.arrayNode(keys.size()) : RestJson.NODES.objectNode();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            JsonNode value = values.get(i);
            Node child = tree.children.get(key);
            boolean deeper = child != ALL && value.isContainerNode() && !(value instanceof PhpObjectNode);
            JsonNode placed = deeper ? NullNode.getInstance() : value;
            if (target instanceof ArrayNode list) {
                list.add(placed);
            } else {
                ((ObjectNode) target).set(key, placed);
            }
            if (deeper) {
                pending.push(new Pending(value, child, target, key, i));
            }
        }
        return target;
    }

    /** Whether the keys are 0, 1, 2 and so on in order. */
    private static boolean sequence(List<String> keys) {
        for (int i = 0; i < keys.size(); i++) {
            if (!keys.get(i).equals(Integer.toString(i))) {
                return false;
            }
        }
        return true;
    }
}

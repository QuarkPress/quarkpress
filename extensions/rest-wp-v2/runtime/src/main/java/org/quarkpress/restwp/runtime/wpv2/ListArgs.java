package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.runtime.ArgChecks;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.SchemaValidator;

/**
 * The list arguments of the collections ({@code include}, {@code exclude}, {@code slug} and the like), held at
 * {@value #MAX_ITEMS} entries each (WordPress has no bound): a longer one is refused as WordPress refuses a list over
 * its schema's {@code maxItems}; and WordPress's readings of their items (texts, absolute ids, positive ids), of a
 * slug list and of a scalar request value.
 */
final class ListArgs {

    /** The most entries a list argument may hold. */
    static final int MAX_ITEMS = 1000;

    private ListArgs() {}

    /** The list argument's items (a scalar is one item); none when it is absent. */
    static List<JsonNode> bounded(RestRequest request, String name) {
        Optional<JsonNode> value = request.param(name);
        if (value.isEmpty()) {
            return List.of();
        }
        List<JsonNode> items = new ArrayList<>();
        if (value.get().isContainerNode()) {
            value.get().forEach(items::add);
        } else {
            items.add(value.get());
        }
        if (items.size() > MAX_ITEMS) {
            throw overBoundParam(name, items.size());
        }
        return items;
    }

    /**
     * The schema's error for the argument {@code name} holding a list of {@code count} entries, over
     * {@value #MAX_ITEMS}: WordPress's error for a list over its schema's {@code maxItems}.
     */
    static RestError overBound(String name, int count) {
        ObjectNode bound = RestJson.NODES.objectNode();
        bound.put("type", "array");
        bound.put("maxItems", MAX_ITEMS);
        ArrayNode list = RestJson.NODES.arrayNode();
        for (int i = 0; i < count; i++) {
            list.add("");
        }
        return SchemaValidator.validate(list, bound, name).orElseThrow();
    }

    /** {@link #overBound}'s error as the request's invalid parameter, answered with 400. */
    static RestException overBoundParam(String name, int count) {
        return new RestException(ArgChecks.invalidParams(Map.of(name, overBound(name, count))));
    }

    /**
     * WordPress's slug list, such as the posts' {@code status}: a list's scalar items, or any other value's text split
     * on runs of commas and whitespace (an object, which the schema refuses first, reads as its empty text); each
     * item passed through {@code sanitize}, those left empty dropped, each kept once in
     * the order of its first appearance. At most {@value #MAX_ITEMS} items are read, as a list argument holds at most
     * that many; the endpoint's schema refuses a longer list.
     */
    static List<String> slugList(JsonNode value, UnaryOperator<String> sanitize) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        List<String> raw = new ArrayList<>();
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (raw.size() == MAX_ITEMS) {
                    break;
                }
                if (item.isValueNode() && !item.isNull()) {
                    raw.add(item.asText());
                }
            }
        } else {
            List<String> pieces = PhpValues.parseList(value.asText());
            raw.addAll(pieces.size() > MAX_ITEMS ? pieces.subList(0, MAX_ITEMS) : pieces);
        }
        Set<String> slugs = new LinkedHashSet<>();
        for (String item : raw) {
            String slug = sanitize.apply(item);
            if (slug != null && !slug.isEmpty()) {
                slugs.add(slug);
            }
        }
        return List.copyOf(slugs);
    }

    /** A scalar request value as PHP prints it (null as ""); a list or an object as "". */
    static String scalar(JsonNode value) {
        return value == null || value.isContainerNode() ? "" : PhpValues.toPhpString(value);
    }

    /** Each item as {@link #scalar} prints it, in order. */
    static List<String> texts(List<JsonNode> items) {
        List<String> texts = new ArrayList<>(items.size());
        items.forEach(item -> texts.add(scalar(item)));
        return texts;
    }

    /** WordPress's id lists: each item's absolute integer value, each once, in order. */
    static List<Long> absoluteIds(List<JsonNode> items) {
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode item : items) {
            long id = PhpValues.toLong(item);
            ids.add(id == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(id));
        }
        return List.copyOf(ids);
    }

    /** The ids above 0, in order, each made an id by {@code of}. */
    static <T> List<T> positive(List<Long> ids, LongFunction<T> of) {
        List<T> positive = new ArrayList<>(ids.size());
        for (long id : ids) {
            if (id > 0) {
                positive.add(of.apply(id));
            }
        }
        return positive;
    }
}

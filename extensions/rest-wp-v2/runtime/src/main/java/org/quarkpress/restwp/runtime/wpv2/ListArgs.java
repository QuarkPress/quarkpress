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
 * its schema's {@code maxItems}; and WordPress's readings of their items (texts, absolute ids, positive ids) and of a
 * scalar request value.
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
            ObjectNode bound = RestJson.NODES.objectNode();
            bound.put("type", "array");
            bound.put("maxItems", MAX_ITEMS);
            ArrayNode list = RestJson.NODES.arrayNode();
            items.forEach(list::add);
            RestError tooMany = SchemaValidator.validate(list, bound, name).orElseThrow();
            throw new RestException(ArgChecks.invalidParams(Map.of(name, tooMany)));
        }
        return items;
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

package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.runtime.ArgChecks;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.SchemaValidator;

/**
 * The list arguments of the collections ({@code include}, {@code exclude}, {@code slug} and the like), held at
 * {@value #MAX_ITEMS} entries each (WordPress has no bound): a longer one is refused as WordPress refuses a list over
 * its schema's {@code maxItems}.
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
}

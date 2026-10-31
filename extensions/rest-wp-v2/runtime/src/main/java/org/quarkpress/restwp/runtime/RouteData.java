package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.RestUrls;

/**
 * WordPress's description of a route, as the indexes and OPTIONS answer it: {@code namespace}, {@code methods} (every
 * method of every endpoint shown in the index, in order, repeats kept), {@code endpoints} (each with its
 * {@code methods}, {@code allow_batch} when it allows batching, and its {@code args}), {@code schema} in the help
 * context, and a {@code self} link for a route without parameters.
 */
public final class RouteData {

    /** The JSON-schema keywords an argument's description shows. */
    static final Set<String> ALLOWED_KEYWORDS = Set.of(
            "title",
            "description",
            "default",
            "type",
            "format",
            "enum",
            "items",
            "properties",
            "additionalProperties",
            "patternProperties",
            "minProperties",
            "maxProperties",
            "minimum",
            "maximum",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "multipleOf",
            "minLength",
            "maxLength",
            "pattern",
            "minItems",
            "maxItems",
            "uniqueItems",
            "anyOf",
            "oneOf");

    private RouteData() {}

    /**
     * The route's data, or null when none of its endpoints is shown in the index.
     *
     * @param context {@code help} adds the item schema (without {@code arg_options}) when the route has one
     */
    public static ObjectNode of(RouteTable.Route route, String context, RestUrls urls) {
        ObjectNode data = RestJson.NODES.objectNode();
        data.put("namespace", route.namespace());
        ArrayNode methods = data.putArray("methods");
        ArrayNode endpoints = data.putArray("endpoints");
        for (Endpoint endpoint : route.endpoints()) {
            if (!endpoint.showInIndex()) {
                continue;
            }
            endpoint.methods().forEach(methods::add);
            ObjectNode described = endpoints.addObject();
            ArrayNode own = described.putArray("methods");
            endpoint.methods().forEach(own::add);
            if (endpoint.allowBatch()) {
                described.putObject("allow_batch").put("v1", true);
            }
            if (endpoint.args().isEmpty()) {
                // an empty PHP array prints as a list
                described.putArray("args");
            } else {
                ObjectNode args = described.putObject("args");
                for (Arg arg : endpoint.args()) {
                    args.set(arg.name(), describe(arg));
                }
            }
        }
        if (endpoints.isEmpty()) {
            return null;
        }
        if (context.equals("help") && route.schema() != null) {
            data.set("schema", withoutArgOptions(route.schema().get()));
        }
        if (!route.pattern().template().contains("{")) {
            data.putObject("_links").putArray("self").addObject().put("href", urls.route(route.key()));
        }
        return data;
    }

    /** The argument's allowed keywords in their declared order, then {@code required}. */
    static ObjectNode describe(Arg arg) {
        ObjectNode described = RestJson.NODES.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = arg.schema().fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> keyword = it.next();
            if (ALLOWED_KEYWORDS.contains(keyword.getKey())) {
                described.set(keyword.getKey(), keyword.getValue().deepCopy());
            }
        }
        described.put("required", arg.required());
        return described;
    }

    /** A copy of the item schema whose properties lose their {@code arg_options}. */
    private static ObjectNode withoutArgOptions(ObjectNode schema) {
        ObjectNode copy = schema.deepCopy();
        JsonNode properties = copy.get("properties");
        if (properties instanceof ObjectNode props) {
            for (Iterator<JsonNode> it = props.elements(); it.hasNext(); ) {
                if (it.next() instanceof ObjectNode property) {
                    property.remove("arg_options");
                }
            }
        }
        return copy;
    }
}

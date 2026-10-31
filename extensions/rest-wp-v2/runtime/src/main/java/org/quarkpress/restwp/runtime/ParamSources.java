package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A request's parameters by source, as WordPress keeps them: the JSON body, the form body, the query string, the
 * matched route's groups and the endpoint's defaults. A parameter is read from the first source, in that order, that
 * holds it with a value other than JSON null; so a query parameter beats a route group of the same name.
 */
public final class ParamSources {

    private final ObjectNode json;
    private final ObjectNode body;
    private final ObjectNode query;
    private final ObjectNode url;
    private final ObjectNode defaults;

    /**
     * @param json the JSON body's parameters, empty when the request has none or is not JSON
     * @param body the form body's parameters, empty when the method does not read them
     * @param url the route's groups
     */
    public ParamSources(
            ObjectNode json, ObjectNode body, ObjectNode query, Map<String, String> url, ObjectNode defaults) {
        this.json = Objects.requireNonNull(json, "json");
        this.body = Objects.requireNonNull(body, "body");
        this.query = Objects.requireNonNull(query, "query");
        this.url = RestJson.NODES.objectNode();
        url.forEach(this.url::put);
        this.defaults = Objects.requireNonNull(defaults, "defaults");
    }

    public ObjectNode json() {
        return json;
    }

    public ObjectNode body() {
        return body;
    }

    public ObjectNode query() {
        return query;
    }

    public ObjectNode url() {
        return url;
    }

    public ObjectNode defaults() {
        return defaults;
    }

    /** The sources in precedence order, highest first; sanitization walks them in this order. */
    public List<ObjectNode> order() {
        return List.of(json, body, query, url, defaults);
    }

    /** The value of {@code name} from the first source that holds it with a value other than JSON null. */
    public Optional<JsonNode> get(String name) {
        for (ObjectNode source : order()) {
            JsonNode value = source.get(name);
            if (value != null && !value.isNull()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public boolean has(String name) {
        return get(name).isPresent();
    }

    /** Whether some source holds {@code name}, with any value, JSON null included. */
    public boolean holds(String name) {
        for (ObjectNode source : order()) {
            if (source.has(name)) {
                return true;
            }
        }
        return false;
    }
}

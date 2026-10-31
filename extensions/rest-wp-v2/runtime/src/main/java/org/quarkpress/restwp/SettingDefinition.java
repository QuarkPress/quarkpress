package org.quarkpress.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A registered setting, as WordPress's {@code register_setting} declares one with {@code show_in_rest}: the name
 * {@code /wp/v2/settings} shows, the option that holds it, its JSON schema type ({@code string}, {@code integer},
 * {@code number}, {@code boolean}, {@code array} or {@code object}), its default when the option does not exist, and
 * further schema keywords ({@code title}, {@code description}, {@code format}, {@code enum}, bounds and so on) merged
 * into {@code {"type": type}}. A setting not shown in REST is registered but never read or written through the route.
 *
 * <p>Names starting with {@code qp_} are QuarkPress's own and are refused, for the REST name and for the option.
 */
public record SettingDefinition(
        String restName,
        String optionName,
        String type,
        Optional<JsonNode> defaultValue,
        ObjectNode schema,
        boolean showInRest) {

    private static final List<String> TYPES = List.of("string", "integer", "number", "boolean", "array", "object");

    public SettingDefinition {
        Objects.requireNonNull(restName, "restName");
        Objects.requireNonNull(optionName, "optionName");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(schema, "schema");
        if (restName.isEmpty() || optionName.isEmpty()) {
            throw new IllegalArgumentException("a setting needs a REST name and an option name");
        }
        if (restName.startsWith("qp_")) {
            throw new IllegalArgumentException("setting names starting with qp_ are reserved: " + restName);
        }
        if (optionName.startsWith("qp_")) {
            throw new IllegalArgumentException("options starting with qp_ are reserved: " + optionName);
        }
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("unsupported setting type " + type + " of " + restName);
        }
        if (defaultValue.isPresent() && defaultValue.get().isNull()) {
            defaultValue = Optional.empty();
        }
        defaultValue = defaultValue.map(JsonNode::deepCopy);
        schema = schema.deepCopy();
    }

    /** The schema keywords, as a copy: the record keeps its own. */
    @Override
    public ObjectNode schema() {
        return schema.deepCopy();
    }

    /** The default, as a copy. */
    @Override
    public Optional<JsonNode> defaultValue() {
        return defaultValue.map(JsonNode::deepCopy);
    }
}

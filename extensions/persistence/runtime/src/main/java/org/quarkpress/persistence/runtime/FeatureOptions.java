package org.quarkpress.persistence.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Option names that hold feature switches. */
public final class FeatureOptions {

    /** Site option: JSON object of feature key to boolean. */
    public static final String SITE = "qp_features";

    /** Network option: JSON object of feature key to boolean. */
    public static final String NETWORK = "qp_feature_defaults";

    private FeatureOptions() {}

    /**
     * The switches a stored value holds: its boolean members. Other members, and a value that is no object, are
     * ignored.
     */
    public static Map<String, Boolean> switches(Optional<JsonNode> value) {
        if (value.isEmpty() || !value.get().isObject()) {
            return Map.of();
        }
        Map<String, Boolean> switches = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : value.get().properties()) {
            if (entry.getValue().isBoolean()) {
                switches.put(entry.getKey(), entry.getValue().booleanValue());
            }
        }
        return switches;
    }
}
